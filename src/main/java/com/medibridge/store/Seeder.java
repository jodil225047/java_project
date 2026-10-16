package com.medibridge.store;

import com.medibridge.model.Medicine;
import com.medibridge.model.Outlet;
import com.medibridge.model.OutletType;
import com.medibridge.model.StockEntry;
import com.medibridge.model.StockStatus;
import com.medibridge.service.AuthService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Random;

/**
 * Fills an empty store with fictional demo data: 20 common medicines and 10 fictional outlets
 * scattered a few kilometres around the configured centre point.
 * Every outlet logs in with its code and the demo password below.
 */
public final class Seeder {
    public static final String DEMO_PASSWORD = "demo123";

    private Seeder() {
    }

    private record OutletSpec(String code, String name, OutletType type, String address,
                              double north, double east, boolean verified) {
    }

    private static final OutletSpec[] OUTLETS = {
            new OutletSpec("CGH01", "Lakeside General Hospital - Main Pharmacy", OutletType.HOSPITAL, "Hospital Road", 0.6, 0.4, true),
            new OutletSpec("TCH02", "Taluk Hospital - Outpatient Pharmacy", OutletType.HOSPITAL, "Station Road", -2.8, 1.9, true),
            new OutletSpec("RMC03", "Riverside Medical College Pharmacy", OutletType.HOSPITAL, "College Junction", 4.5, -3.2, true),
            new OutletSpec("GLP04", "GreenLeaf Pharmacy", OutletType.RETAIL, "Market Road", 1.2, -0.9, true),
            new OutletSpec("CCP05", "CityCare Pharmacy", OutletType.RETAIL, "Bus Stand Road", -1.0, -1.6, true),
            new OutletSpec("SRP06", "Sunrise Pharmacy", OutletType.RETAIL, "Temple Junction", 2.4, 2.7, false),
            new OutletSpec("MPP07", "MediPoint Pharmacy", OutletType.RETAIL, "Canal Bank Road", -3.9, -2.2, false),
            new OutletSpec("PGP08", "Palm Grove Pharmacy", OutletType.RETAIL, "Palm Grove Lane", 0.2, 3.8, true),
            new OutletSpec("HVP09", "Hillview Medicals", OutletType.RETAIL, "Hillview Cross", 6.3, 1.1, false),
            new OutletSpec("NLP10", "Nirmal Medicals", OutletType.RETAIL, "North Bazaar", -5.6, 4.0, false),
    };

    private static final String[][] MEDICINES = {
            {"Paracetamol", "500 mg", "Tablet"},
            {"Paracetamol", "650 mg", "Tablet"},
            {"Paracetamol", "120 mg/5 ml", "Syrup"},
            {"Amoxicillin", "500 mg", "Capsule"},
            {"Azithromycin", "500 mg", "Tablet"},
            {"Metformin", "500 mg", "Tablet"},
            {"Amlodipine", "5 mg", "Tablet"},
            {"Atorvastatin", "10 mg", "Tablet"},
            {"Omeprazole", "20 mg", "Capsule"},
            {"Cetirizine", "10 mg", "Tablet"},
            {"Salbutamol", "100 mcg", "Inhaler"},
            {"Insulin Glargine", "100 IU/ml", "Injection"},
            {"Oral Rehydration Salts", "21 g", "Sachet"},
            {"Ibuprofen", "400 mg", "Tablet"},
            {"Pantoprazole", "40 mg", "Tablet"},
            {"Losartan", "50 mg", "Tablet"},
            {"Ciprofloxacin", "500 mg", "Tablet"},
            {"Levothyroxine", "50 mcg", "Tablet"},
            {"Doxycycline", "100 mg", "Capsule"},
            {"Aspirin", "75 mg", "Tablet"},
    };

    /** Ages (minutes since last update) that stock reports are given in the demo data. */
    private static final long[] AGES = {2, 4, 6, 7, 11, 18, 41, 95, 240, 600, 1500, 4320};

    public static void seed(DataStore store, double centerLat, double centerLng) {
        store.resetState();
        DataStore.State s = store.state();

        int medId = 1;
        for (String[] m : MEDICINES) {
            s.medicines.put(medId, new Medicine(medId, m[0], m[1], m[2]));
            medId++;
        }

        int outletId = 1;
        for (OutletSpec o : OUTLETS) {
            double lat = centerLat + o.north() / 111.0;
            double lng = centerLng + o.east() / (111.0 * Math.cos(Math.toRadians(centerLat)));
            String phone = String.format("+91 00000 %05d", outletId);
            s.outlets.put(outletId, new Outlet(outletId, o.code(), o.name(), o.type(), o.address(), phone,
                    round(lat), round(lng), o.verified(), false, AuthService.hashPassword(DEMO_PASSWORD)));
            outletId++;
        }

        // The two stories from the presentation, so the demo tells them straight away.
        // Slide 2: the patient walks from outlet to outlet before finding paracetamol.
        put(s, 1, "Paracetamol", "500 mg", StockStatus.OUT, 9);
        put(s, 4, "Paracetamol", "500 mg", StockStatus.OUT, 14);
        put(s, 5, "Paracetamol", "500 mg", StockStatus.OUT, 22);
        put(s, 6, "Paracetamol", "500 mg", StockStatus.AVAILABLE, 6);
        // Slide 7: freshness matters - the last "available" report is three days old.
        put(s, 1, "Amoxicillin", "500 mg", StockStatus.AVAILABLE, 2);
        put(s, 4, "Amoxicillin", "500 mg", StockStatus.AVAILABLE, 6);
        put(s, 5, "Amoxicillin", "500 mg", StockStatus.LOW, 11);
        put(s, 6, "Amoxicillin", "500 mg", StockStatus.AVAILABLE, 4320);

        // Everything else: deterministic pseudo-random stock so the demo looks the same each time.
        Random rnd = new Random(2026);
        for (int o = 1; o <= OUTLETS.length; o++) {
            for (int m = 1; m <= MEDICINES.length; m++) {
                String k = o + ":" + m;
                if (s.stock.containsKey(k)) continue;
                if (rnd.nextDouble() < 0.25) continue; // this outlet doesn't report this medicine
                double r = rnd.nextDouble();
                StockStatus st = r < 0.55 ? StockStatus.AVAILABLE : r < 0.75 ? StockStatus.LOW : StockStatus.OUT;
                long age = AGES[rnd.nextInt(AGES.length)];
                s.stock.put(k, new StockEntry(o, m, st, Instant.now().minus(age, ChronoUnit.MINUTES), true));
            }
        }

        store.save();
    }

    private static void put(DataStore.State s, int outletId, String name, String strength,
                            StockStatus status, long ageMinutes) {
        for (Medicine m : s.medicines.values()) {
            if (m.name().equals(name) && m.strength().equals(strength)) {
                s.stock.put(outletId + ":" + m.id(), new StockEntry(outletId, m.id(), status,
                        Instant.now().minus(ageMinutes, ChronoUnit.MINUTES), true));
                return;
            }
        }
        throw new IllegalStateException("Unknown seed medicine " + name + " " + strength);
    }

    private static double round(double v) {
        return Math.round(v * 1_000_000d) / 1_000_000d;
    }
}
