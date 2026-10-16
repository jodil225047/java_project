package com.medibridge.model;

import java.io.Serializable;
import java.time.Instant;

public record AuditEntry(long id, int outletId, Instant at, String action, String detail) implements Serializable {
}
