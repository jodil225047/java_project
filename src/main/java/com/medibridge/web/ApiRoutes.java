package com.medibridge.web;

import com.medibridge.model.Alert;
import com.medibridge.model.AuditEntry;
import com.medibridge.model.Medicine;
import com.medibridge.model.Outlet;
import com.medibridge.model.StockEntry;
import com.medibridge.model.StockStatus;
import com.medibridge.service.AuthService;
import com.medibridge.service.SearchService;
import com.medibridge.store.DataStore;
import com.medibridge.util.Config;
import com.medibridge.util.ServiceException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** All /api/* endpoints. Each route takes a Request and returns an object that is sent as JSON. */
public final class ApiRoutes {
    private static final int MAX_BODY_BYTES = 64 * 1024;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,200}\\.[^@\\s]{2,}$");
    private static final Pattern PHONE = Pattern.compile("^\\+?[0-9][0-9 \\-]{6,17}$");

    @FunctionalInterface
    private interface Route {
        Object handle(Request r) throws Exception;
    }

    /** What a route handler gets to see about the incoming call. */
    private static final class Request {
        final Map<String, String> query;
        final Map<String, Object> body;
        final String token;
        final AuthService auth;

        Request(Map<String, String> query, Map<String, Object> body, String token, AuthService auth) {
            this.query = query;
            this.body = body;
            this.token = token;
            this.auth = auth;
        }

        int outletId() {
            Integer id = auth.outletIdFor(token);
            if (id == null) throw new ServiceException(401, "Please log in again");
            return id;
        }

        String text(String name, int maxLen) {
            Object v = body.get(name);
            if (!(v instanceof String s) || s.isBlank()) throw new ServiceException(400, name + " is required");
            if (s.length() > maxLen) throw new ServiceException(400, name + " is too long");
            return s.trim();
        }

        int integer(String name) {
            Object v = body.get(name);
            if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) return n.intValue();
            throw new ServiceException(400, name + " must be a whole number");
        }

        boolean bool(String name) {
            if (body.get(name) instanceof Boolean b) return b;
            throw new ServiceException(400, name + " must be true or false");
        }

        double queryDouble(String name, double def, double min, double max) {
            String v = query.get(name);
            if (v == null || v.isBlank()) return def;
            try {
                double d = Double.parseDouble(v);
                if (Double.isNaN(d) || d < min || d > max) throw new NumberFormatException();
                return d;
            } catch (NumberFormatException e) {
                throw new ServiceException(400, name + " must be a number between " + min + " and " + max);
            }
        }
    }

    private final DataStore store;
    private final SearchService search;
    private final AuthService auth;
    private final Config cfg;
    private final Map<String, Route> routes = new HashMap<>();
    private final Set<String> knownPaths = new HashSet<>();

    public ApiRoutes(DataStore store, SearchService search, AuthService auth, Config cfg) {
        this.store = store;
        this.search = search;
        this.auth = auth;
        this.cfg = cfg;
        add("GET", "/api/config", this::config);
        add("GET", "/api/medicines", this::medicines);
        add("GET", "/api/search", this::search);
        add("POST", "/api/alerts", this::createAlert);
        add("GET", "/api/alerts", this::listAlerts);
        add("POST", "/api/alerts/cancel", this::cancelAlert);
        add("GET", "/api/metrics", r -> store.metrics());
        add("POST", "/api/outlet/login", this::login);
        add("POST", "/api/outlet/logout", this::logout);
        add("GET", "/api/outlet/me", this::me);
        add("POST", "/api/outlet/stock", this::setStock);
        add("POST", "/api/outlet/visibility", this::setVisibility);
        add("POST", "/api/outlet/pause", this::setPaused);
        add("GET", "/api/outlet/audit", this::audit);
    }

    private void add(String method, String path, Route r) {
        routes.put(method + " " + path, r);
        knownPaths.add(path);
    }

    // ------------------------------------------------------------------ dispatch

    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            Route route = routes.get(method + " " + path);
            if (route == null) {
                if (knownPaths.contains(path)) throw new ServiceException(405, "Method not allowed");
                throw new ServiceException(404, "Unknown endpoint");
            }
            Map<String, Object> body = new HashMap<>();
            if (method.equals("POST")) {
                byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
                if (raw.length > MAX_BODY_BYTES) throw new ServiceException(413, "Request too large");
                if (raw.length > 0) {
                    Object parsed = Json.parse(new String(raw, StandardCharsets.UTF_8));
                    if (!(parsed instanceof Map<?, ?> m)) throw new ServiceException(400, "Body must be a JSON object");
                    for (Map.Entry<?, ?> e : m.entrySet()) body.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            Request req = new Request(parseQuery(ex.getRequestURI().getRawQuery()), body, bearer(ex), auth);
            send(ex, 200, route.handle(req));
        } catch (ServiceException e) {
            send(ex, e.status(), Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            send(ex, 400, Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            e.printStackTrace();
            send(ex, 500, Map.of("error", "Something went wrong on the server"));
        }
    }

    private static void send(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = Json.write(payload).getBytes(StandardCharsets.UTF_8);
        StaticHandler.securityHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> q = new HashMap<>();
        if (raw == null || raw.isEmpty()) return q;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            q.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return q;
    }

    // ------------------------------------------------------------------ public endpoints

    private Object config(Request r) {
        Map<String, Object> center = new LinkedHashMap<>();
        center.put("lat", cfg.centerLat());
        center.put("lng", cfg.centerLng());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("demoCenter", center);
        out.put("weights", SearchService.weights());
        List<String> forms = new ArrayList<>();
        for (Medicine m : store.allMedicines()) if (!forms.contains(m.form())) forms.add(m.form());
        forms.sort(String::compareTo);
        out.put("forms", forms);
        return out;
    }

    private Object medicines(Request r) {
        String q = r.query.getOrDefault("q", "");
        List<Map<String, Object>> out = new ArrayList<>();
        List<Medicine> list = q.isBlank() ? store.allMedicines() : store.findMedicines(q, null, 50);
        for (Medicine m : list) out.add(SearchService.medicineJson(m));
        return out;
    }

    private Object search(Request r) {
        double lat = r.queryDouble("lat", cfg.centerLat(), -90, 90);
        double lng = r.queryDouble("lng", cfg.centerLng(), -180, 180);
        double radius = r.queryDouble("radius", 15, 1, 50);
        String q = r.query.get("q");
        if (q != null && q.length() > 80) throw new ServiceException(400, "q is too long");
        return search.search(q, r.query.get("form"), lat, lng, radius);
    }

    private Object createAlert(Request r) {
        int medicineId = r.integer("medicineId");
        String contact = r.text("contact", 100);
        if (!EMAIL.matcher(contact).matches() && !PHONE.matcher(contact).matches()) {
            throw new ServiceException(400, "Enter a valid e-mail address or phone number");
        }
        return alertJson(store.addAlert(medicineId, contact));
    }

    private Object listAlerts(Request r) {
        String keys = r.query.getOrDefault("keys", "");
        List<String> list = new ArrayList<>();
        for (String k : keys.split(",")) if (!k.isBlank() && k.length() <= 64) list.add(k.trim());
        if (list.size() > 50) throw new ServiceException(400, "Too many alerts requested");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Alert a : store.alertsByKeys(list)) out.add(alertJson(a));
        return out;
    }

    private Object cancelAlert(Request r) {
        boolean found = store.cancelAlert(r.text("key", 64));
        if (!found) throw new ServiceException(404, "Alert not found");
        return Map.of("ok", true);
    }

    private Map<String, Object> alertJson(Alert a) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("key", a.key());
        Medicine m = store.medicine(a.medicineId());
        j.put("medicine", m == null ? "Unknown" : m.label());
        j.put("contact", DataStore.maskContact(a.contact()));
        j.put("ageMinutes", Duration.between(a.createdAt(), Instant.now()).toMinutes());
        j.put("status", a.cancelled() ? "CANCELLED" : a.triggeredAt() != null ? "TRIGGERED" : "WAITING");
        j.put("triggeredBy", a.triggeredBy());
        j.put("triggeredAgoMinutes", a.triggeredAt() == null ? null
                : Duration.between(a.triggeredAt(), Instant.now()).toMinutes());
        return j;
    }

    // ------------------------------------------------------------------ outlet portal

    private Object login(Request r) {
        String token = auth.login(r.text("code", 20), r.text("password", 100));
        Integer id = auth.outletIdFor(token);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("outlet", outletJson(store.outlet(id)));
        return out;
    }

    private Object logout(Request r) {
        auth.logout(r.token);
        return Map.of("ok", true);
    }

    private Object me(Request r) {
        int id = r.outletId();
        Outlet o = store.outlet(id);
        Instant now = Instant.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<Integer> listed = new HashSet<>();
        for (StockEntry e : store.stockForOutlet(id)) {
            Medicine m = store.medicine(e.medicineId());
            if (m == null) continue;
            listed.add(m.id());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("medicineId", m.id());
            row.put("label", m.label());
            row.put("status", e.status());
            row.put("ageMinutes", Math.max(0, Duration.between(e.updatedAt(), now).toMinutes()));
            row.put("visible", e.publicVisible());
            row.put("searches", store.searchCount(m.id()));
            row.put("alertsWaiting", store.activeAlertCount(m.id()));
            rows.add(row);
        }
        rows.sort(Comparator.comparing(x -> String.valueOf(x.get("label"))));
        List<Map<String, Object>> unlisted = new ArrayList<>();
        for (Medicine m : store.allMedicines()) if (!listed.contains(m.id())) unlisted.add(SearchService.medicineJson(m));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outlet", outletJson(o));
        out.put("stock", rows);
        out.put("unlisted", unlisted);
        return out;
    }

    private Object setStock(Request r) {
        int id = r.outletId();
        StockStatus st = StockStatus.parse(r.text("status", 20));
        List<Alert> fired = store.setStock(id, r.integer("medicineId"), st);
        return Map.of("ok", true, "alertsTriggered", fired.size());
    }

    private Object setVisibility(Request r) {
        int id = r.outletId();
        List<Alert> fired = store.setVisibility(id, r.integer("medicineId"), r.bool("visible"));
        return Map.of("ok", true, "alertsTriggered", fired.size());
    }

    private Object setPaused(Request r) {
        int id = r.outletId();
        List<Alert> fired = store.setPaused(id, r.bool("paused"));
        return Map.of("ok", true, "alertsTriggered", fired.size());
    }

    private Object audit(Request r) {
        int id = r.outletId();
        Instant now = Instant.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (AuditEntry a : store.auditFor(id, 30)) {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("action", a.action());
            j.put("detail", a.detail());
            j.put("ageMinutes", Math.max(0, Duration.between(a.at(), now).toMinutes()));
            out.add(j);
        }
        return out;
    }

    private static Map<String, Object> outletJson(Outlet o) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("id", o.id());
        j.put("code", o.code());
        j.put("name", o.name());
        j.put("type", o.type());
        j.put("address", o.address());
        j.put("verified", o.verified());
        j.put("paused", o.paused());
        return j;
    }
}
