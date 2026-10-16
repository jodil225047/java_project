package com.medibridge.service;

import com.medibridge.model.Outlet;
import com.medibridge.store.DataStore;
import com.medibridge.util.ServiceException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Outlet authentication: PBKDF2 password hashes, random session tokens (kept in memory),
 * and a simple lock-out after repeated wrong passwords.
 */
public final class AuthService {
    private static final int ITERATIONS = 100_000;
    private static final Duration SESSION_LIFETIME = Duration.ofHours(8);
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(1);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String DUMMY_HASH = hashPassword("not-a-real-password");

    private record Session(int outletId, Instant expires) {
    }

    private record Failures(int count, Instant since) {
    }

    private final DataStore store;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new HashMap<>();

    public AuthService(DataStore store) {
        this.store = store;
    }

    /** Returns a session token, or throws ServiceException(401/429). */
    public String login(String code, String password) {
        String key = code == null ? "" : code.trim().toUpperCase();
        checkLockout(key);
        Outlet o = store.outletByCode(key);
        // Always do one hash comparison, so response time doesn't reveal whether the code exists.
        boolean ok = verifyPassword(password == null ? "" : password, o == null ? DUMMY_HASH : o.passwordHash());
        if (o == null || !ok) {
            recordFailure(key);
            throw new ServiceException(401, "Wrong outlet code or password");
        }
        synchronized (failures) {
            failures.remove(key);
        }
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String token = HexFormat.of().formatHex(raw);
        sessions.put(token, new Session(o.id(), Instant.now().plus(SESSION_LIFETIME)));
        return token;
    }

    /** Returns the outlet id for a valid token, or null. */
    public Integer outletIdFor(String token) {
        if (token == null) return null;
        Session s = sessions.get(token);
        if (s == null) return null;
        if (Instant.now().isAfter(s.expires())) {
            sessions.remove(token);
            return null;
        }
        return s.outletId();
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }

    private void checkLockout(String key) {
        synchronized (failures) {
            Failures f = failures.get(key);
            if (f == null) return;
            if (Instant.now().isAfter(f.since().plus(LOCKOUT))) {
                failures.remove(key);
            } else if (f.count() >= MAX_FAILURES) {
                throw new ServiceException(429, "Too many failed attempts. Try again in a minute.");
            }
        }
    }

    private void recordFailure(String key) {
        synchronized (failures) {
            Failures f = failures.get(key);
            failures.put(key, f == null ? new Failures(1, Instant.now()) : new Failures(f.count() + 1, f.since()));
        }
    }

    // ------------------------------------------------------------------ password hashing

    public static String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(pbkdf2(password, salt));
    }

    static boolean verifyPassword(String password, String stored) {
        String[] parts = stored.split(":");
        if (parts.length != 2) return false;
        byte[] salt = Base64.getDecoder().decode(parts[0]);
        byte[] expected = Base64.getDecoder().decode(parts[1]);
        return MessageDigest.isEqual(expected, pbkdf2(password, salt));
    }

    private static byte[] pbkdf2(String password, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
