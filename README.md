# MediBridge - Local Medicine Availability Network

College project (SDG 3 - Good Health & Well-being, target 3.8).
Patients search **once** and see which nearby hospital and retail pharmacies last reported a medicine
as available, **how fresh** that report is, and how far away it is.

> The platform shows reported availability only. It does **not** prescribe or recommend medication.

## Quick start

You need **JDK 17 or newer** and **Maven 3.6+** (check with `java -version` and `mvn -version`).

```bash
mvn clean package          # builds target/medibridge.jar
java -jar target/medibridge.jar
```

Then open **http://localhost:8080**

Shortcuts: `run.bat` (Windows) or `./run.sh` (Linux / macOS) do both steps for you.

| Option | Example | Meaning |
|---|---|---|
| `--port=` | `--port=9090` | use another port if 8080 is busy |
| `--reset` | `--reset` | throw away saved data and recreate the demo data |
| `--lat=` `--lng=` | `--lat=12.97 --lng=77.59` | centre of the fictional demo outlets (put them in your own town) |
| `--host=0.0.0.0` | | let other devices on your Wi-Fi open the site (default: this computer only) |
| `--no-persist` | | keep everything in memory only |

Data is saved to `data/medibridge.dat` (created next to where you run the jar).

## Demo

* **Find medicine** - try `paracetamol 500` (the "walk from outlet to outlet" story from slide 2) and
  `amoxicillin` (the "stale 3-day-old report" story from slide 7). Open *Why this rank?* on any result.
* **Outlet portal** - log in with outlet code `CGH01` (also `TCH02, RMC03, GLP04, CCP05, SRP06, MPP07, PGP08, HVP09, NLP10`),
  password `demo123`. Change a status, hide a medicine, pause the outlet, read the audit log.
* **My alerts** - search with "Use my location" far from the demo area (or pick a medicine nobody stocks), choose
  *Notify me*, then report that medicine as *Available* in the outlet portal and watch the alert turn green.
* **Impact** - live metrics (match rate, freshness, alerts) and the ranking weights.

All outlets, phone numbers and stock are **fictional demo data**.

## How it maps to the project slides

| Slide | Where it is in the code |
|---|---|
| 2 The problem (outlet-to-outlet search) | demo data for `paracetamol 500` |
| 5 User journey: search -> match -> compare -> act | `SearchService`, `static/js/app.js` (results, Directions / Call buttons, alerts) |
| 6 Pharmacy & hospital portal | Outlet portal tab, `ApiRoutes` (`/api/outlet/*`), audit log in `DataStore` |
| 7 Freshness-aware availability | `SearchService.freshnessLabel`, stale warnings in the UI, demo data for `amoxicillin` |
| 8 System architecture | `web` (API layer) -> `service` (core engine) -> `store` (data layer) |
| 9 Intelligence layer | `SearchService` - transparent, explainable ranking |
| 10 SDG impact: what to measure | `/api/metrics` and the Impact tab (match rate, freshness, alerts) |

### The ranking formula

```
score = 0.40 * availability + 0.30 * freshness + 0.15 * verification + 0.15 * distance
```

| Factor | Value |
|---|---|
| availability | available = 1, low = 0.5, out of stock = 0 |
| freshness | `0.5 ^ (minutes since update / 180)` - halves every 3 hours |
| verification | verified outlet = 1, unverified = 0.5 |
| distance | `1 - km / search radius` |

Reports with stock are always listed before out-of-stock ones; the score orders each group.
Change the weights at the top of `SearchService.java`.

## Project layout

```
pom.xml
src/main/java/com/medibridge/
  Main.java                 starts the server
  model/                    Medicine, Outlet, StockEntry, Alert, AuditEntry ...
  store/DataStore.java      all data + file persistence (swap for MySQL later)
  store/Seeder.java         fictional demo data
  service/SearchService     ranking engine
  service/AuthService       PBKDF2 password hashing, sessions, lock-out
  web/                      ApiRoutes (REST/JSON), StaticHandler, Json
  util/                     Config, GeoUtil (haversine distance)
src/main/resources/static/  index.html, css/style.css, js/app.js
```

No third-party libraries are used: the web server is the JDK's built-in `com.sun.net.httpserver`.

## REST API (JSON)

| Method & path | Purpose |
|---|---|
| `GET /api/search?q=&form=&lat=&lng=&radius=` | ranked availability per matching medicine |
| `GET /api/medicines?q=` | medicine catalogue / autocomplete |
| `POST /api/alerts` `{medicineId, contact}` | create an availability alert |
| `GET /api/alerts?keys=` | status of alerts, by private key |
| `POST /api/alerts/cancel` `{key}` | cancel an alert |
| `GET /api/metrics` | impact metrics |
| `POST /api/outlet/login` `{code, password}` | outlet log-in, returns a bearer token |
| `GET /api/outlet/me` | dashboard data (needs token) |
| `POST /api/outlet/stock` `{medicineId, status}` | add / update a stock report |
| `POST /api/outlet/visibility` `{medicineId, visible}` | show / hide a report from the public |
| `POST /api/outlet/pause` `{paused}` | pause / resume the whole outlet |
| `GET /api/outlet/audit` | recent changes by this outlet |

## Security notes

* Outlet passwords are stored as salted PBKDF2-SHA256 hashes; 5 wrong attempts lock a code for a minute.
* Alerts are looked up by a random private key kept in the patient's browser - never by phone / e-mail - and
  contact details are masked everywhere they are displayed.
* All server data is inserted into the page with `textContent`, and a strict Content-Security-Policy is sent.
* **Before any real deployment:** change the demo password, put the site behind HTTPS, and add outlet
  registration / approval.

## Known limits and ideas for next phases

* Storage is a single file, which is fine for a pilot but is not a database. The deck proposes MySQL: only
  `DataStore` would need to change.
* Alert "notifications" are printed to the server console. Plug in an SMS / e-mail provider in `DataStore.fireAlerts`.
* Outlets are seeded, not self-registered. A sign-up + approval flow would be the next step.
* Medicine names are a small fixed catalogue (20 items); a real deployment would load a full drug list.
