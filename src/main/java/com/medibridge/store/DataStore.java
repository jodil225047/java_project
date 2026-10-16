package com.medibridge.store;

import com.medibridge.model.Alert;
import com.medibridge.model.AuditEntry;
import com.medibridge.model.Medicine;
import com.medibridge.model.Outlet;
import com.medibridge.model.StockEntry;
import com.medibridge.model.StockStatus;
import com.medibridge.util.ServiceException;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * All application data lives here. Every public method is synchronized, so the multi-threaded
 * web server can call it safely. After each change the whole state is saved to a single file,
 * which is plenty for a pilot-sized network (a few hundred outlets).
 *
 * To move to MySQL later, replace this class with a JDBC-backed version that keeps the same public
 * methods - nothing else in the project touches the data directly.
 */
public final class DataStore {

    /** Everything that is persisted. Plain collections of immutable records. */
    static final class State implements Serializable {
        private static final long serialVersionUID = 1L;
        final LinkedHashMap<Integer, Medicine> medicines = new LinkedHashMap<>();
        final LinkedHashMap<Integer, Outlet> outlets = new LinkedHashMap<>();
        final LinkedHashMap<String, StockEntry> stock = new LinkedHashMap<>();
        final ArrayList<AuditEntry> audit = new ArrayList<>();
        final ArrayList<Alert> alerts = new ArrayList<>();
        final HashMap<Integer, Integer> searchesByMedicine = new HashMap<>();
        long searches;
        long searchesWithStock;
        long alertsTriggered;
        long nextAuditId = 1;
        long nextAlertId = 1;
    }

    private static final int MAX_AUDIT_ENTRIES = 2000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private State s = new State();
    private final Path file; // null => do not persist

    public DataStore(Path file) {
        this.file = file;
    }

    // ------------------------------------------------------------------ persistence

    /** Loads saved state. Returns false (and keeps an empty state) if there is nothing usable. */
    public synchronized boolean load() {
        if (file == null || !Files.isRegularFile(file)) return false;
        try (InputStream in = Files.newInputStream(file);
             ObjectInputStream ois = new ObjectInputStream(in)) {
            s = (State) ois.readObject();
            return !s.outlets.isEmpty();
        } catch (IOException | ClassNotFoundException | ClassCastException e) {
            System.err.println("Could not read " + file + " (" + e.getMessage() + ") - starting from demo data.");
            s = new State();
            return false;
        }
    }

    synchronized State state() {
        return s;
    }

    synchronized void resetState() {
        s = new State();
    }

    public synchronized void save() {
        if (file == null) return;
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "medibridge", ".tmp");
            try (OutputStream out = Files.newOutputStream(tmp);
                 ObjectOutputStream oos = new ObjectOutputStream(out)) {
                oos.writeObject(s);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("Warning: could not save data: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ medicines

    public synchronized List<Medicine> allMedicines() {
        return new ArrayList<>(s.medicines.values());
    }

    public synchronized Medicine medicine(int id) {
        return s.medicines.get(id);
    }

    /**
     * Every word typed must appear somewhere in "name strength form", so "paracetamol 500" matches
     * "Paracetamol 500 mg Tablet". An optional form filter narrows the result further.
     */
    public synchronized List<Medicine> findMedicines(String query, String form, int limit) {
        String[] tokens = query == null ? new String[0] : query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Medicine> out = new ArrayList<>();
        for (Medicine m : s.medicines.values()) {
            if (form != null && !form.isBlank() && !m.form().equalsIgnoreCase(form.trim())) continue;
            String haystack = m.label().toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String t : tokens) {
                if (!t.isEmpty() && !haystack.contains(t)) {
                    all = false;
                    break;
                }
            }
            if (all) out.add(m);
            if (out.size() >= limit) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ outlets & stock

    public synchronized Outlet outlet(int id) {
        return s.outlets.get(id);
    }

    public synchronized Outlet outletByCode(String code) {
        if (code == null) return null;
        for (Outlet o : s.outlets.values()) {
            if (o.code().equalsIgnoreCase(code.trim())) return o;
        }
        return null;
    }

    public synchronized List<Outlet> allOutlets() {
        return new ArrayList<>(s.outlets.values());
    }

    public synchronized List<StockEntry> stockForMedicine(int medicineId) {
        List<StockEntry> out = new ArrayList<>();
        for (StockEntry e : s.stock.values()) {
            if (e.medicineId() == medicineId) out.add(e);
        }
        return out;
    }

    public synchronized List<StockEntry> stockForOutlet(int outletId) {
        List<StockEntry> out = new ArrayList<>();
        for (StockEntry e : s.stock.values()) {
            if (e.outletId() == outletId) out.add(e);
        }
        return out;
    }

    private static String key(int outletId, int medicineId) {
        return outletId + ":" + medicineId;
    }

    /** Adds or updates a stock report. Returns the alerts this update triggered. */
    public synchronized List<Alert> setStock(int outletId, int medicineId, StockStatus status) {
        Outlet o = requireOutlet(outletId);
        Medicine m = requireMedicine(medicineId);
        StockEntry old = s.stock.get(key(outletId, medicineId));
        boolean visible = old == null || old.publicVisible();
        s.stock.put(key(outletId, medicineId), new StockEntry(outletId, medicineId, status, Instant.now(), visible));
        audit(outletId, old == null ? "ADD_STOCK" : "UPDATE_STOCK",
                m.label() + ": " + (old == null ? "" : old.status() + " -> ") + status);
        List<Alert> fired = List.of();
        if (status == StockStatus.AVAILABLE && visible && !o.paused()) {
            fired = fireAlerts(medicineId, o.name());
        }
        save();
        return fired;
    }

    public synchronized List<Alert> setVisibility(int outletId, int medicineId, boolean visible) {
        Outlet o = requireOutlet(outletId);
        Medicine m = requireMedicine(medicineId);
        StockEntry e = s.stock.get(key(outletId, medicineId));
        if (e == null) throw new ServiceException(404, "This outlet has no report for that medicine");
        s.stock.put(key(outletId, medicineId),
                new StockEntry(outletId, medicineId, e.status(), e.updatedAt(), visible));
        audit(outletId, "VISIBILITY", m.label() + ": " + (visible ? "shown to public" : "hidden from public"));
        List<Alert> fired = List.of();
        if (visible && e.status() == StockStatus.AVAILABLE && !o.paused()) {
            fired = fireAlerts(medicineId, o.name());
        }
        save();
        return fired;
    }

    public synchronized List<Alert> setPaused(int outletId, boolean paused) {
        Outlet o = requireOutlet(outletId);
        s.outlets.put(outletId, o.withPaused(paused));
        audit(outletId, paused ? "PAUSE" : "RESUME", paused ? "Outlet hidden from search" : "Outlet visible in search");
        List<Alert> fired = new ArrayList<>();
        if (!paused) {
            for (StockEntry e : stockForOutlet(outletId)) {
                if (e.publicVisible() && e.status() == StockStatus.AVAILABLE) {
                    fired.addAll(fireAlerts(e.medicineId(), o.name()));
                }
            }
        }
        save();
        return fired;
    }

    private Outlet requireOutlet(int id) {
        Outlet o = s.outlets.get(id);
        if (o == null) throw new ServiceException(404, "Unknown outlet");
        return o;
    }

    private Medicine requireMedicine(int id) {
        Medicine m = s.medicines.get(id);
        if (m == null) throw new ServiceException(404, "Unknown medicine");
        return m;
    }

    // ------------------------------------------------------------------ audit log

    private void audit(int outletId, String action, String detail) {
        s.audit.add(new AuditEntry(s.nextAuditId++, outletId, Instant.now(), action, detail));
        if (s.audit.size() > MAX_AUDIT_ENTRIES) {
            s.audit.subList(0, s.audit.size() - MAX_AUDIT_ENTRIES).clear();
        }
    }

    /** Newest first. */
    public synchronized List<AuditEntry> auditFor(int outletId, int limit) {
        List<AuditEntry> out = new ArrayList<>();
        for (int i = s.audit.size() - 1; i >= 0 && out.size() < limit; i--) {
            AuditEntry a = s.audit.get(i);
            if (a.outletId() == outletId) out.add(a);
        }
        return out;
    }

    // ------------------------------------------------------------------ alerts

    public synchronized Alert addAlert(int medicineId, String contact) {
        requireMedicine(medicineId);
        // Avoid piling up duplicates from the same person for the same medicine.
        for (Alert a : s.alerts) {
            if (a.isActive() && a.medicineId() == medicineId && a.contact().equalsIgnoreCase(contact)) return a;
        }
        byte[] raw = new byte[12];
        RANDOM.nextBytes(raw);
        Alert a = new Alert(s.nextAlertId++, HexFormat.of().formatHex(raw), medicineId, contact,
                Instant.now(), null, null, false);
        s.alerts.add(a);
        save();
        return a;
    }

    public synchronized List<Alert> alertsByKeys(List<String> keys) {
        List<Alert> out = new ArrayList<>();
        for (Alert a : s.alerts) {
            if (keys.contains(a.key())) out.add(a);
        }
        return out;
    }

    public synchronized boolean cancelAlert(String key) {
        for (int i = 0; i < s.alerts.size(); i++) {
            Alert a = s.alerts.get(i);
            if (a.key().equals(key)) {
                if (a.isActive()) {
                    s.alerts.set(i, a.cancel());
                    save();
                }
                return true;
            }
        }
        return false;
    }

    public synchronized int activeAlertCount(int medicineId) {
        int n = 0;
        for (Alert a : s.alerts) {
            if (a.isActive() && a.medicineId() == medicineId) n++;
        }
        return n;
    }

    /** Marks every waiting alert for this medicine as triggered. */
    private List<Alert> fireAlerts(int medicineId, String outletName) {
        List<Alert> fired = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < s.alerts.size(); i++) {
            Alert a = s.alerts.get(i);
            if (a.isActive() && a.medicineId() == medicineId) {
                Alert t = a.triggered(now, outletName);
                s.alerts.set(i, t);
                s.alertsTriggered++;
                fired.add(t);
                // A real deployment would send an SMS / e-mail here.
                System.out.println("[ALERT] would notify " + maskContact(a.contact()) + ": "
                        + s.medicines.get(medicineId).label() + " is now available at " + outletName);
            }
        }
        return fired;
    }

    /** Contact details are never sent back to browsers or logged in full. */
    public static String maskContact(String contact) {
        if (contact == null || contact.isEmpty()) return "";
        int at = contact.indexOf('@');
        if (at > 0) {
            return contact.substring(0, Math.min(2, at)) + "***" + contact.substring(at);
        }
        String digits = contact.replaceAll("\\D", "");
        return digits.length() <= 3 ? "***" : "*** *** " + digits.substring(digits.length() - 3);
    }

    // ------------------------------------------------------------------ search statistics

    public synchronized void recordSearch(List<Integer> medicineIds, boolean hadStock) {
        s.searches++;
        if (hadStock) s.searchesWithStock++;
        for (int id : medicineIds) s.searchesByMedicine.merge(id, 1, Integer::sum);
        save();
    }

    public synchronized int searchCount(int medicineId) {
        return s.searchesByMedicine.getOrDefault(medicineId, 0);
    }

    public synchronized Map<String, Object> metrics() {
        Map<String, Object> m = new LinkedHashMap<>();
        int activeOutlets = 0;
        for (Outlet o : s.outlets.values()) if (!o.paused()) activeOutlets++;
        int listings = 0;
        int fresh = 0;
        Instant now = Instant.now();
        for (StockEntry e : s.stock.values()) {
            Outlet o = s.outlets.get(e.outletId());
            if (!e.publicVisible() || o == null || o.paused()) continue;
            listings++;
            if (java.time.Duration.between(e.updatedAt(), now).toMinutes() <= 60) fresh++;
        }
        long created = s.alerts.size();
        m.put("outlets", s.outlets.size());
        m.put("activeOutlets", activeOutlets);
        m.put("medicines", s.medicines.size());
        m.put("liveListings", listings);
        m.put("freshListingsPct", listings == 0 ? 0 : Math.round(100.0 * fresh / listings));
        m.put("searches", s.searches);
        m.put("matchRatePct", s.searches == 0 ? 0 : Math.round(100.0 * s.searchesWithStock / s.searches));
        m.put("alertsCreated", created);
        m.put("alertsTriggered", s.alertsTriggered);
        return m;
    }
}
