package com.medibridge.model;

import java.io.Serializable;

/**
 * A hospital pharmacy or retail pharmacy that reports its own stock.
 * "verified" = trusted outlet whose reports are audited (used in ranking).
 */
public record Outlet(int id, String code, String name, OutletType type, String address, String phone,
                     double lat, double lng, boolean verified, boolean paused,
                     String passwordHash) implements Serializable {

    public Outlet withPaused(boolean p) {
        return new Outlet(id, code, name, type, address, phone, lat, lng, verified, p, passwordHash);
    }
}
