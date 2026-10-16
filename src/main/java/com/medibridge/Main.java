package com.medibridge;

import com.medibridge.service.AuthService;
import com.medibridge.service.SearchService;
import com.medibridge.store.DataStore;
import com.medibridge.store.Seeder;
import com.medibridge.util.Config;
import com.medibridge.web.ApiRoutes;
import com.medibridge.web.StaticHandler;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * MediBridge - local medicine availability network.
 *
 * Run:  java -jar target/medibridge.jar [--port=8080] [--reset] [--host=0.0.0.0]
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        Config cfg = Config.load(args);

        DataStore store = new DataStore(cfg.persist() ? cfg.dataFile() : null);
        boolean loaded = !cfg.reset() && store.load();
        if (!loaded) {
            System.out.println("Creating demo data (10 fictional outlets, 20 medicines)...");
            Seeder.seed(store, cfg.centerLat(), cfg.centerLng());
        }

        AuthService auth = new AuthService(store);
        SearchService search = new SearchService(store);
        ApiRoutes api = new ApiRoutes(store, search, auth, cfg);

        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.host(), cfg.port()), 0);
        server.createContext("/api/", api::handle);
        server.createContext("/", new StaticHandler());
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.start();

        System.out.println();
        System.out.println("MediBridge is running:  http://" + ("0.0.0.0".equals(cfg.host()) ? "localhost" : cfg.host())
                + ":" + cfg.port() + "/");
        System.out.println("Outlet portal demo login:  code CGH01 (or TCH02, GLP04 ...)  password " + Seeder.DEMO_PASSWORD);
        System.out.println("Press Ctrl+C to stop.");
    }
}
