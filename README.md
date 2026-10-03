# Seat Reservation Service

A Spring Boot and PostgreSQL API for creating shows and reserving seats with transactional concurrency control, per-user limits, cancellation, and idempotent requests.

## Run Locally

Requirements: Docker Compose, `curl`, and `jq` for the burst script.

```sh
docker compose up --build
```

The app listens on `http://localhost:8080`; PostgreSQL data persists in the Compose volume. To override local settings, set the variables shown in `.env.example` in your shell or a Compose `.env` file. For a deployment, use a strong `ADMIN_TOKEN` and database credentials rather than local defaults.

```sh
curl http://localhost:8080/health/live
curl http://localhost:8080/health/ready
curl http://localhost:8080/metrics
```

The image build runs `mvn package`, including unit tests. The local host does not need Maven installed.

## Test Console

Start the service with Docker Compose, then run the React test console in a second terminal:

```sh
cd ui
npm install
npm run dev
```

Open the Vite URL (normally `http://localhost:5173`). The dev server proxies API requests to `http://localhost:8080`; leave the API field blank to use that local proxy, or enter an API base URL if the target permits browser CORS. The console supports show setup, reservation/retry and cancellation, multi-seat and identity probes, concurrent hot-seat/per-user/idempotency/cancellation races, health checks, metrics, request IDs, and API error inspection. The in-browser race count is capped at 250; use `scripts/burst.sh` for larger runs.

## API

Create a show (admin token):

```sh
curl -X POST http://localhost:8080/shows \
  -H 'Authorization: Bearer local-admin-token' \
  -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000,"per_user_limit":2}'
```

The response includes the UUID `id`, `price_paise`, `per_user_limit`, each seat's status, and reconciled counts. `per_user_limit` defaults to 4. CamelCase aliases are also accepted for the two numeric request fields.

Reserve seats (user token and required idempotency key):

```sh
curl -X POST http://localhost:8080/shows/<SHOW_ID>/reserve \
  -H 'Authorization: Bearer user-123' \
  -H 'Idempotency-Key: request-123' \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A1"]}'
```

A new reservation returns `201`; an identical retry returns the original reservation with `200`. Reusing a key with different seats returns `409 IDEMPOTENCY_KEY_REUSED`. Seat allocation is all-or-nothing. Expected conflicts include `SEAT_TAKEN` and `PER_USER_LIMIT_EXCEEDED`.

Cancel an owned reservation and read show state:

```sh
curl -X POST http://localhost:8080/reservations/<RESERVATION_ID>/cancel \
  -H 'Authorization: Bearer user-123'
curl http://localhost:8080/shows/<SHOW_ID>
```

Cancellation releases the seats transactionally. Repeating cancellation is successful and does not change state. User identity is derived from the bearer token, never from the request body. This exercise-level token scheme is not a substitute for production identity validation.

Errors use `{ "error": { "code": "...", "message": "..." }, "request_id": "..." }`. The service returns `503` for database access failures and does not expose database messages to clients.

## Correctness and Observability

- Seat rows are locked in sorted seat-number order inside the reservation transaction.
- A `(show_id, user_id)` coordination row is locked before checking the active-seat limit, serializing concurrent requests for that user/show.
- PostgreSQL unique constraints protect seat definitions and `(show, user, idempotency key)` records.
- Request fingerprints use SHA-256 over the show ID and sorted seat list.
- Amounts are integer paise. There is no payment-provider integration.
- `GET /health/live` is independent of PostgreSQL; `GET /health/ready` runs a database query.
- Prometheus metrics are available at `/metrics`; labels use bounded decline reasons, not user or reservation IDs.
- `X-Request-ID` is validated or generated and returned on every response.

## Concurrency Check

```sh
./scripts/burst.sh http://localhost:8080
```

The script checks hot-seat contention, a concurrent per-user limit, simultaneous identical idempotency requests, same-key mismatch behavior, 5xx/network errors, and final seat reconciliation. Configure `REQUESTS` and `CONCURRENCY` to change the hot-seat test size.

## Load Test

`bin/load-test.sh` runs a configurable hot-seat benchmark and reports throughput, latency percentiles, response classes, and final inventory reconciliation:

```sh
./bin/load-test.sh http://localhost:8080
REQUESTS=20000 CONCURRENCY=500 ./bin/load-test.sh http://localhost:8080
```

The default is 1,000 requests at concurrency 100. The maximum request count is 20,000. Set `ADMIN_TOKEN` for non-local environments, `REQUEST_TIMEOUT` to change the per-request timeout, or `KEEP_RESULTS=1` to retain the raw status/latency results. This load test intentionally targets one seat; use `scripts/burst.sh` for the broader invariant suite.

## Render Deployment

The root `render.yaml` defines a Docker web service and private Postgres database. The Docker image builds the React UI into Spring Boot's static resources, so the API and UI share one origin. The Render service uses `/health/ready` as its deployment health check and generates `ADMIN_TOKEN` in Render. Free plans may sleep or have storage/time limits; verify the current Render plan restrictions before relying on the deployment for long-term persistence or high-volume tests.

## Configuration

See `.env.example`. The main settings are `PORT`, `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and `ADMIN_TOKEN`. Flyway migrations live in `src/main/resources/db/migration` and are applied on application startup.

## Current Boundaries

The service has no temporary `HELD` state, payment integration, JWT identity provider, rate limiting, structured JSON logging, distributed tracing, or production alert definitions. A cancellation/reservation contention test and deployment-specific load test remain useful follow-up verification before production use.
