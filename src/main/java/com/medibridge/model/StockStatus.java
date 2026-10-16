package com.medibridge.model;

/** Availability state reported by an outlet for one medicine. */
public enum StockStatus {
    AVAILABLE, LOW, OUT;

    public static StockStatus parse(String s) {
        if (s == null) throw new IllegalArgumentException("status is required");
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status must be AVAILABLE, LOW or OUT");
        }
    }
}
