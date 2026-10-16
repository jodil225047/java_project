package com.medibridge.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/** Serves the HTML / CSS / JS files packaged inside the jar under /static. */
public final class StaticHandler implements HttpHandler {
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "json", "application/json; charset=utf-8");

    /** Browser-side protections: only our own scripts / styles run, and the page can't be framed. */
    static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                ex.getResponseHeaders().set("Allow", "GET, HEAD");
                ex.sendResponseHeaders(405, -1);
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.endsWith("/")) path += "index.html";
            if (path.contains("..") || path.contains("\\") || path.contains("//")) {
                ex.sendResponseHeaders(400, -1);
                return;
            }
            try (InputStream in = StaticHandler.class.getResourceAsStream("/static" + path)) {
                if (in == null) {
                    byte[] msg = "Not found".getBytes();
                    securityHeaders(ex);
                    ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                    ex.sendResponseHeaders(404, method.equals("HEAD") ? -1 : msg.length);
                    if (!method.equals("HEAD")) ex.getResponseBody().write(msg);
                    return;
                }
                byte[] data = in.readAllBytes();
                String ext = path.substring(path.lastIndexOf('.') + 1).toLowerCase();
                securityHeaders(ex);
                ex.getResponseHeaders().set("Content-Type", TYPES.getOrDefault(ext, "application/octet-stream"));
                ex.getResponseHeaders().set("Cache-Control", "no-cache");
                ex.sendResponseHeaders(200, method.equals("HEAD") ? -1 : data.length);
                if (!method.equals("HEAD")) ex.getResponseBody().write(data);
            }
        } finally {
            ex.close();
        }
    }
}
