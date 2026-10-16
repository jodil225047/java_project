package com.medibridge.model;

import java.io.Serializable;
import java.time.Instant;

public record StockEntry(int outletId, int medicineId, StockStatus status, Instant updatedAt,
                         boolean publicVisible) implements Serializable {
}
