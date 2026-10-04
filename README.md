# Seat Reservation Service

A Spring Boot and PostgreSQL API for creating shows and reserving seats with transactional concurrency control, per-user limits, cancellation, and idempotent requests.

## Run Locally

Requirements: Docker Compose, `curl`, and `jq` for the burst script.

```sh
docker compose up --build
```

The app listens on `http://localhost:8080`; PostgreSQL data persists in the Compose volume. To override local settings, set the variables shown in `.env.example` in your shell or a Compose `.env` file.

```sh
curl http://localhost:8080/health/live
curl http://localhost:8080/health/ready
```

Health checks are unauthenticated; business endpoints and `/metrics` require a JWT. The image build runs `mvn package`, including unit tests. The local host does not need Maven installed.

## Test Console

Start the service with Docker Compose, then run the React test console in a second terminal:

```sh
cd ui
npm install
npm run dev
```

Open the Vite URL (normally `http://localhost:5173`) and sign in with an account below. The dev server proxies API requests to `http://localhost:8080`; leave the API field blank to use that local proxy, or enter an API base URL if the target permits browser CORS. The JWT stays in browser memory and is cleared on sign-out or expiry. The console supports show setup (admins), reservation/retry and cancellation, multi-seat and identity probes, concurrent hot-seat/per-user/idempotency/cancellation races, health checks, admin metrics, request IDs, and API error inspection. The in-browser race count is capped at 250; use `scripts/burst.sh` for larger runs.

## API Usage

The hosted API URL is <https://seat-reservation-api-tv2k.onrender.com>. On 2026-10-04, the hosted readiness endpoint returned `200`, login returned `200`, and an unauthenticated request to create a show returned `401`, confirming that authentication is active on the hosted service. These checks do not expose the deployed commit SHA, so they do not independently confirm that every commit on this branch is deployed. For the verified local instance, use `http://localhost:8080`. Anonymous access is limited to login, health checks, and the static UI; all business APIs require a signed JWT. Show creation and metrics are admin-only. Reservations and cancellations use the authenticated JWT subject as the user ID.

### Assessment Accounts

These seeded accounts are for assessment/demo use only. They are not production credentials; change or remove the seeder before using this service with real users. Passwords are stored as BCrypt hashes in PostgreSQL.

| Role | User ID | Password |
| --- | --- | --- |
| ADMIN | `admin01` | `SeatAdmin-2026!01` |
| ADMIN | `admin02` | `SeatAdmin-2026!02` |
| USER | `user01` | `SeatUser-2026!01` |
| USER | `user02` | `SeatUser-2026!02` |
| USER | `user03` | `SeatUser-2026!03` |
| USER | `user04` | `SeatUser-2026!04` |
| USER | `user05` | `SeatUser-2026!05` |
| USER | `user06` | `SeatUser-2026!06` |
| USER | `user07` | `SeatUser-2026!07` |
| USER | `user08` | `SeatUser-2026!08` |
| USER | `user09` | `SeatUser-2026!09` |
| USER | `user10` | `SeatUser-2026!10` |

Login with `POST /auth/login`. The JWT expires after one hour; the response is `Cache-Control: no-store`.

```sh
export BASE_URL=http://localhost:8080
ADMIN_TOKEN=$(curl -fsS -X POST "$BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin01","password":"SeatAdmin-2026!01"}' | jq -er '.access_token')
USER_TOKEN=$(curl -fsS -X POST "$BASE_URL/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"username":"user01","password":"SeatUser-2026!01"}' | jq -er '.access_token')
```

```sh
export BASE_URL=https://seat-reservation-api-tv2k.onrender.com
```

### Endpoint Reference

| Method | Endpoint | Authentication | Purpose |
| --- | --- | --- | --- |
| `GET` | `/health/live` | None | Check that the application process is responding. |
| `GET` | `/health/ready` | None | Check application and database readiness. |
| `POST` | `/auth/login` | None | Verify account credentials and issue a signed JWT. |
| `GET` | `/metrics` | ADMIN JWT | Read Prometheus metrics. |
| `POST` | `/shows` | ADMIN JWT | Create a show and its seats. |
| `GET` | `/shows/{showId}` | USER or ADMIN JWT | Read show details, seat statuses, and counts. |
| `POST` | `/shows/{showId}/reserve` | USER or ADMIN JWT and `Idempotency-Key` | Reserve one or more seats for the JWT subject. |
| `POST` | `/reservations/{reservationId}/cancel` | Owning USER or ADMIN JWT | Cancel a reservation and release its seats. |

### Create, Reserve, Inspect, Cancel

Check the hosted service, then create a show as an admin. Save its returned ID for the following requests:

```sh
curl -fsS "$BASE_URL/health/live" | jq
curl -fsS "$BASE_URL/health/ready" | jq

show=$(curl -fsS -X POST "$BASE_URL/shows" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"api-demo","seats":["A1","A2","A3"],"price_paise":25000,"per_user_limit":2}')
printf '%s\n' "$show" | jq
SHOW_ID=$(printf '%s' "$show" | jq -er '.id')
```

Reserve a seat with a user JWT and an idempotency key. The service takes the user ID from the validated JWT subject, ignoring any user ID supplied in JSON. Use the same key and same body to retry safely; that retry returns the original reservation with `200` rather than creating another one.

```sh
IDEMPOTENCY_KEY=api-demo-001
reservation=$(curl -fsS -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A1"]}')
printf '%s\n' "$reservation" | jq
RESERVATION_ID=$(printf '%s' "$reservation" | jq -er '.reservation_id')

curl -fsS "$BASE_URL/shows/$SHOW_ID" -H "Authorization: Bearer $USER_TOKEN" | jq '.counts, .seats'
curl -fsS -X POST "$BASE_URL/reservations/$RESERVATION_ID/cancel" \
  -H "Authorization: Bearer $USER_TOKEN" | jq
```

A new reservation returns `201`; an identical retry returns `200`. Reusing a key with different seats returns `409 IDEMPOTENCY_KEY_REUSED`. Seat allocation is all-or-nothing; other expected conflicts include `SEAT_TAKEN` and `PER_USER_LIMIT_EXCEEDED`. Cancellation releases seats transactionally, and repeating a cancellation does not change state.

For local testing, set `BASE_URL=http://localhost:8080` and log in to the local seeded accounts. The API validates JWT signatures, expiry, and roles; user identity is derived from the JWT subject, not the request body.

Errors use `{ "error": { "code": "...", "message": "..." }, "request_id": "..." }`. The service returns `503` for database access failures and does not expose database messages to clients.

## Correctness and Observability

- Seat rows are locked in sorted seat-number order inside the reservation transaction.
- A `(show_id, user_id)` coordination row is locked before checking the active-seat limit, serializing concurrent requests for that user/show.
- PostgreSQL unique constraints protect seat definitions and `(show, user, idempotency key)` records.
- Request fingerprints use SHA-256 over the show ID and sorted seat list.
- Amounts are integer paise. There is no payment-provider integration.
- `GET /health/live` is independent of PostgreSQL; `GET /health/ready` runs a database query.
- Prometheus metrics are available at `/metrics`; labels use bounded decline reasons, not user or reservation IDs.
- `seats_available` is a database-backed aggregate gauge; confirmation, decline-reason, cancellation, and idempotent-replay counters are exposed at `/metrics`.
- `X-Request-ID` is validated or generated, returned on every response, and included in JSON request logs along with method, path, status, and duration. Authorization headers and request bodies are not logged.
- Render logs can be inspected by an authenticated operator with `render logs --resources srv-db0i4qgu01pc73aftkvg --tail`; Render does not provide public log access for this service.

## Concurrency Check

```sh
./scripts/burst.sh http://localhost:8080
```

The script logs in with seeded accounts, then checks hot-seat contention, a concurrent per-user limit, simultaneous identical idempotency requests, same-key mismatch, missing/malformed authentication and idempotency headers, all-or-nothing multi-seat allocation, JWT-subject identity, non-owner/repeated cancellation, canceled-key replay, cancellation/rebooking races, 5xx/network errors, and final seat reconciliation. Configure `REQUESTS` and `CONCURRENCY` to change the hot-seat test size. Override `ADMIN_USERNAME`/`ADMIN_PASSWORD` and `USER01_USERNAME`/`USER01_PASSWORD` through `USER08_USERNAME`/`USER08_PASSWORD` when using different seeded accounts.

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

The default is 1,000 requests at concurrency 100. The maximum request count is 20,000. Set `REQUEST_TIMEOUT` to change the per-request timeout, or `KEEP_RESULTS=1` to retain the raw status/latency results. This load test intentionally targets one seat; use `scripts/burst.sh` for the broader invariant suite.

## Render Deployment

The root `render.yaml` defines a Docker web service and private Postgres database. The Docker image builds the React UI into Spring Boot's static resources, so the API and UI share one origin. The Render service uses `/health/ready` as its deployment health check and generates a `JWT_SECRET`. Only login, health checks, and static UI files are anonymous; show, reservation, cancellation, and metrics APIs enforce authentication and roles. Free plans may sleep or have storage/time limits; verify current Render plan restrictions before relying on the deployment for long-term persistence or high-volume tests.

## Configuration

See `.env.example`. The main settings are `PORT`, `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and `JWT_SECRET` (at least 32 bytes). The server signs HS256 tokens with a one-hour lifetime. Flyway migrations live in `src/main/resources/db/migration` and are applied on application startup.

## Current Boundaries

The service has no temporary `HELD` state, payment integration, external JWT identity provider/OIDC, token refresh or revocation, rate limiting, distributed tracing, or production alert definitions. The seeded assessment accounts are unsuitable for production. Structured JSON request logs are available only through authenticated Render access; no public log viewer or screen recording is provided. A 20,000-request burst against the live free-tier service remains unverified.
