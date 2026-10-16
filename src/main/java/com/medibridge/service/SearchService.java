package com.medibridge.service;

import com.medibridge.model.Medicine;
import com.medibridge.model.Outlet;
import com.medibridge.model.StockEntry;
import com.medibridge.model.StockStatus;
import com.medibridge.store.DataStore;
import com.medibridge.util.GeoUtil;
import com.medibridge.util.ServiceException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds a medicine across all participating outlets and ranks the reports.
 *
 * The ranking is deliberately transparent (project deck, "Intelligence layer"). Each report gets
 * four scores between 0 and 1, combined with fixed weights:
 *
 *   score = 0.40 * availability + 0.30 * freshness + 0.15 * verification + 0.15 * distance
 *
 *   availability : AVAILABLE = 1.0, LOW = 0.5, OUT = 0
 *   freshness    : halves every 3 hours since the outlet's last update  (0.5 ^ (minutes / 180))
 *   verification : verified/audited outlet = 1.0, unverified = 0.5
 *   distance     : 1 - km / search radius  (0 at the edge of the radius)
 *
 * Reports with stock are always listed before "out of stock" ones; the score orders each group.
 */
public final class SearchService {
    public static final double W_AVAILABILITY = 0.40;
    public static final double W_FRESHNESS = 0.30;
    public static final double W_VERIFICATION = 0.15;
    public static final double W_DISTANCE = 0.15;
    public static final double FRESHNESS_HALF_LIFE_MIN = 180.0;

    private final DataStore store;

    public SearchService(DataStore store) {
        this.store = store;
    }

    private record Scored(Map<String, Object> row, double score, boolean out) {
    }

    public Map<String, Object> search(String query, String form, double lat, double lng, double radiusKm) {
        if (query == null || query.trim().length() < 2) {
            throw new ServiceException(400, "Enter at least 2 characters of the medicine name");
        }
        List<Medicine> matches = store.findMedicines(query, form, 8);
        Instant now = Instant.now();
        List<Map<String, Object>> groups = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        boolean anyStock = false;

        for (Medicine m : matches) {
            ids.add(m.id());
            List<Scored> scored = new ArrayList<>();
            for (StockEntry e : store.stockForMedicine(m.id())) {
                if (!e.publicVisible()) continue;
                Outlet o = store.outlet(e.outletId());
                if (o == null || o.paused()) continue;
                double km = GeoUtil.distanceKm(lat, lng, o.lat(), o.lng());
                if (km > radiusKm) continue;
                scored.add(score(e, o, km, radiusKm, now));
            }
            scored.sort(Comparator.comparing(Scored::out).thenComparing(Comparator.comparingDouble(Scored::score).reversed()));

            List<Map<String, Object>> rows = new ArrayList<>();
            for (Scored sc : scored) {
                rows.add(sc.row());
                if (!sc.out()) anyStock = true;
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("medicine", medicineJson(m));
            g.put("results", rows);
            groups.add(g);
        }
        if (!matches.isEmpty()) store.recordSearch(ids, anyStock);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", query.trim());
        out.put("radiusKm", radiusKm);
        out.put("groups", groups);
        return out;
    }

    private Scored score(StockEntry e, Outlet o, double km, double radiusKm, Instant now) {
        long ageMin = Math.max(0, Duration.between(e.updatedAt(), now).toMinutes());
        double availability = switch (e.status()) {
            case AVAILABLE -> 1.0;
            case LOW -> 0.5;
            case OUT -> 0.0;
        };
        double freshness = Math.pow(0.5, ageMin / FRESHNESS_HALF_LIFE_MIN);
        double verification = o.verified() ? 1.0 : 0.5;
        double distance = Math.max(0.0, 1.0 - km / radiusKm);
        double total = W_AVAILABILITY * availability + W_FRESHNESS * freshness
                + W_VERIFICATION * verification + W_DISTANCE * distance;

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("availability", pct(availability));
        breakdown.put("freshness", pct(freshness));
        breakdown.put("verification", pct(verification));
        breakdown.put("distance", pct(distance));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("outletId", o.id());
        row.put("name", o.name());
        row.put("type", o.type());
        row.put("verified", o.verified());
        row.put("address", o.address());
        row.put("phone", o.phone());
        row.put("status", e.status());
        row.put("ageMinutes", ageMin);
        row.put("freshness", freshnessLabel(ageMin));
        row.put("distanceKm", Math.round(km * 10.0) / 10.0);
        row.put("score", pct(total));
        row.put("breakdown", breakdown);
        row.put("directionsUrl", "https://www.google.com/maps/dir/?api=1&destination=" + o.lat() + "," + o.lng());
        return new Scored(row, total, e.status() == StockStatus.OUT);
    }

    public static String freshnessLabel(long ageMinutes) {
        if (ageMinutes <= 30) return "FRESH";
        if (ageMinutes <= 360) return "RECENT";
        if (ageMinutes <= 1440) return "AGING";
        return "STALE";
    }

    private static int pct(double v) {
        return (int) Math.round(v * 100);
    }

    public static Map<String, Object> medicineJson(Medicine m) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("id", m.id());
        j.put("name", m.name());
        j.put("strength", m.strength());
        j.put("form", m.form());
        j.put("label", m.label());
        return j;
    }

    public static Map<String, Object> weights() {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("availability", pct(W_AVAILABILITY));
        w.put("freshness", pct(W_FRESHNESS));
        w.put("verification", pct(W_VERIFICATION));
        w.put("distance", pct(W_DISTANCE));
        return w;
    }
}
