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

## API Usage

The deployed API and test console share this base URL: <https://seat-reservation-api-tv2k.onrender.com>. For a local instance, use `http://localhost:8080` instead. Creating shows is intentionally public for assessment testing; no admin token is required. Reservations and cancellations still use bearer tokens as user identities to enforce ownership and per-user limits.

```sh
export BASE_URL=https://seat-reservation-api-tv2k.onrender.com
```

### Endpoint Reference

| Method | Endpoint | Authentication | Purpose |
| --- | --- | --- | --- |
| `GET` | `/health/live` | None | Check that the application process is responding. |
| `GET` | `/health/ready` | None | Check application and database readiness. |
| `GET` | `/metrics` | None | Read Prometheus metrics. |
| `POST` | `/shows` | None (public) | Create a show and its seats. |
| `GET` | `/shows/{showId}` | None | Read show details, seat statuses, and counts. |
| `POST` | `/shows/{showId}/reserve` | User bearer token and `Idempotency-Key` | Reserve one or more seats. |
| `POST` | `/reservations/{reservationId}/cancel` | Owning user's bearer token | Cancel a reservation and release its seats. |

### Create, Reserve, Inspect, Cancel

Check the hosted service, then create a show. Save its returned ID for the following requests:

```sh
curl -fsS "$BASE_URL/health/live" | jq
curl -fsS "$BASE_URL/health/ready" | jq

show=$(curl -fsS -X POST "$BASE_URL/shows" \
  -H 'Content-Type: application/json' \
  -d '{"name":"api-demo","seats":["A1","A2","A3"],"price_paise":25000,"per_user_limit":2}')
printf '%s\n' "$show" | jq
SHOW_ID=$(printf '%s' "$show" | jq -er '.id')
```

Reserve a seat with a user token and an idempotency key. Use the same key and same body to retry safely; that retry returns the original reservation with `200` rather than creating another one.

```sh
USER_TOKEN=user-demo
IDEMPOTENCY_KEY=api-demo-001
reservation=$(curl -fsS -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A1"]}')
printf '%s\n' "$reservation" | jq
RESERVATION_ID=$(printf '%s' "$reservation" | jq -er '.reservation_id')

curl -fsS "$BASE_URL/shows/$SHOW_ID" | jq '.counts, .seats'
curl -fsS -X POST "$BASE_URL/reservations/$RESERVATION_ID/cancel" \
  -H "Authorization: Bearer $USER_TOKEN" | jq
```

A new reservation returns `201`; an identical retry returns `200`. Reusing a key with different seats returns `409 IDEMPOTENCY_KEY_REUSED`. Seat allocation is all-or-nothing; other expected conflicts include `SEAT_TAKEN` and `PER_USER_LIMIT_EXCEEDED`. Cancellation releases seats transactionally, and repeating a cancellation does not change state.

For local testing, set `BASE_URL=http://localhost:8080`. User identity is derived from the reservation/cancellation bearer token, not the request body. These exercise-level tokens are identifiers, not production identity validation.

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

To run the same invariant suite against Render, pass the hosted base URL:

```sh
REQUESTS=20 CONCURRENCY=10 ./scripts/burst.sh "$BASE_URL"
```

The script creates a show and reservation records in the target database and does not clean them up. Keep hosted test sizes modest, especially on the free service.

## Load Test

`bin/load-test.sh` runs a configurable hot-seat benchmark and reports throughput, latency percentiles, response classes, and final inventory reconciliation:

```sh
./bin/load-test.sh http://localhost:8080
REQUESTS=25 CONCURRENCY=5 ./bin/load-test.sh "$BASE_URL"
REQUESTS=20000 CONCURRENCY=500 ./bin/load-test.sh http://localhost:8080
```

The default is 1,000 requests at concurrency 100. The maximum request count is 20,000. Set `ADMIN_TOKEN` for non-local environments, `REQUEST_TIMEOUT` to change the per-request timeout, or `KEEP_RESULTS=1` to retain the raw status/latency results. This load test intentionally targets one seat; use `scripts/burst.sh` for the broader invariant suite.

## Render Deployment

The root `render.yaml` defines a Docker web service and private Postgres database. The Docker image builds the React UI into Spring Boot's static resources, so the API and UI share one origin. The Render service uses `/health/ready` as its deployment health check. The public `POST /shows` endpoint can be used by anyone and has no rate limiting; this is intended only for assessment/demo use. Free plans may sleep or have storage/time limits; verify the current Render plan restrictions before relying on the deployment for long-term persistence or high-volume tests.

## Configuration

See `.env.example`. The main settings are `PORT`, `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and `ADMIN_TOKEN`. Flyway migrations live in `src/main/resources/db/migration` and are applied on application startup.

## Current Boundaries

The service has no temporary `HELD` state, payment integration, JWT identity provider, rate limiting, structured JSON logging, distributed tracing, or production alert definitions. A cancellation/reservation contention test and deployment-specific load test remain useful follow-up verification before production use.
