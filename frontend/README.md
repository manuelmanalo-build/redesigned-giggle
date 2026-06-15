# Trade Ops Demo Frontend

Lightweight Angular demo UI for the `realtime-trade-processing-simulator` backend.

This is an operational interview demo, not a production trading workstation. It exercises the backend lifecycle:

- Dashboard metrics from paginated search endpoints.
- Order submission with `Idempotency-Key`.
- Order search with filters and pagination.
- Order detail with execution reports and trades.
- Cancel and replace actions for eligible orders.
- Diagnostics view for health, metrics links, and current outbox/inbox limitations.

## Tech Stack

- Angular 22.0.1.
- TypeScript 6.0.
- Standalone components.
- Angular Router.
- Angular Signals for local UI state.
- Reactive forms.
- Angular HTTP client.
- Plain CSS.

The local machine currently has Node 24.14.1, while Angular 22 requires Node 24.15.0 or newer on the Node 24 line. The npm scripts run Angular through a temporary npm-provided Node 24.15 runtime, so no global Node upgrade is required.

## Local Demo

From the repository root, start backend dependencies and the Spring Boot app:

```powershell
docker compose up -d
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```

In a second terminal:

```powershell
cd frontend
npm install
npm start
```

Open:

```text
http://localhost:4200
```

The dev server proxies `/api`, `/actuator`, and `/v3` to `http://localhost:8080`, which avoids browser CORS setup for the local demo.

## Backend URL Configuration

Default:

```ts
backendBaseUrl: ''
```

That means the Angular app calls relative paths such as `/api/v1/orders` and relies on `proxy.conf.json`.

For another backend URL, open the Diagnostics screen and set `Backend base URL`, for example:

```text
http://localhost:8080
```

The value is stored in browser `localStorage` under `tradeDemo.backendBaseUrl`.

## Commands

```powershell
npm start
npm run build
npm test
```

Build output is written to:

```text
frontend/dist/frontend
```

## Notes

- The API client is hand-coded from the backend OpenAPI contract and DTOs.
- Diagnostics REST endpoints for `outbox_events` and `processed_messages` do not exist in the backend MVP. The Diagnostics screen states that honestly and links to Actuator/OpenAPI.
- There is no authentication, WebSocket streaming, or charting by design.
