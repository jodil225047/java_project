package com.medibridge.util;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Runtime settings. Each can be given as a command-line flag (--port=9090), a JVM property
 * (-Dport=9090) or an environment variable (PORT=9090), in that order of priority.
 */
public record Config(String host, int port, double centerLat, double centerLng,
                     Path dataFile, boolean persist, boolean reset) {

    public static Config load(String[] args) {
        Map<String, String> flags = new HashMap<>();
        for (String a : args) {
            if (!a.startsWith("--")) continue;
            int eq = a.indexOf('=');
            if (eq > 0) flags.put(a.substring(2, eq), a.substring(eq + 1));
            else flags.put(a.substring(2), "true");
        }
        String host = pick(flags, "host", "HOST", "127.0.0.1");
        int port = Integer.parseInt(pick(flags, "port", "PORT", "8080"));
        // Demo outlets are generated around this point. Change it to your own town.
        double lat = Double.parseDouble(pick(flags, "lat", "CENTER_LAT", "10.30"));
        double lng = Double.parseDouble(pick(flags, "lng", "CENTER_LNG", "76.33"));
        Path file = Path.of(pick(flags, "data", "DATA_FILE", "data/medibridge.dat"));
        boolean persist = !Boolean.parseBoolean(pick(flags, "no-persist", "NO_PERSIST", "false"));
        boolean reset = Boolean.parseBoolean(pick(flags, "reset", "RESET", "false"));
        return new Config(host, port, lat, lng, file, persist, reset);
    }

    private static String pick(Map<String, String> flags, String name, String env, String def) {
        if (flags.containsKey(name)) return flags.get(name);
        String prop = System.getProperty(name);
        if (prop != null) return prop;
        String e = System.getenv(env);
        return e != null ? e : def;
    }
}
