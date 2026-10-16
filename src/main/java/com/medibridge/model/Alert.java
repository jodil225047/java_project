package com.medibridge.model;

import java.io.Serializable;
import java.time.Instant;

/**
 * "Tell me when this medicine becomes available somewhere."
 * The random {@code key} is the only handle the patient holds - alerts are never looked up by
 * contact details, so nobody can browse another person's medicine interests.
 */
public record Alert(long id, String key, int medicineId, String contact, Instant createdAt,
                    Instant triggeredAt, String triggeredBy, boolean cancelled) implements Serializable {

    public boolean isActive() {
        return triggeredAt == null && !cancelled;
    }

    public Alert triggered(Instant at, String outletName) {
        return new Alert(id, key, medicineId, contact, createdAt, at, outletName, cancelled);
    }

    public Alert cancel() {
        return new Alert(id, key, medicineId, contact, createdAt, triggeredAt, triggeredBy, true);
    }
}
