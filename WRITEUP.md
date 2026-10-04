# Seat Reservation at Scale

## Design Summary

This service prioritizes durable, single-owner seat allocation under concurrent requests. PostgreSQL is the source of truth; request decisions and state transitions run in database transactions. The implementation uses Spring Boot 3, Java 21, PostgreSQL, JDBC, Flyway, Actuator, and Micrometer.

## Implemented Invariants

- A seat has at most one current reservation. Its state and owning reservation are updated together in one transaction.
- A multi-seat request either reserves all requested seats or none.
- Active seats per `(show, user)` do not exceed the show's configured limit. A persistent coordination row is created and locked before counting existing seats and deciding the request.
- Retries with the same `(show, user, idempotency key)` and same canonical body return the original reservation. A changed body is rejected with `409 IDEMPOTENCY_KEY_REUSED`.
- Money uses integer paise (`BIGINT` in PostgreSQL); reservation totals use checked integer multiplication.
- Show counts are derived from persisted seat rows, so `available + held + confirmed = total` by construction. The current state machine does not use `HELD`.
- Reservations and cancellations use the `sub` claim from a signature- and expiry-validated JWT; JSON cannot choose the user identity.
- The service issues one-hour HS256 JWTs from `POST /auth/login`. Accounts are stored with BCrypt password hashes; the application runs statelessly.
- Authorization is role-based: admins create shows and read metrics; users and admins read shows and make reservation requests. Cancellation additionally verifies reservation ownership.

## Reservation Transaction

1. Validate the key and canonicalize requested seats by sorting them.
2. Confirm the show exists and lock the `(show_id, user_id)` coordination row.
3. Check the idempotency record and compare its SHA-256 request fingerprint.
4. Lock requested seat rows in sorted order with `FOR UPDATE`.
5. Verify every requested seat exists and is available.
6. Count the user's confirmed seats and check the per-show limit.
7. Insert one reservation and update all seats and reservation-seat relationships.
8. Commit; any domain failure rolls the transaction back.

The user/show lock protects the count-plus-reserve decision across requests from the same user. Seat locks serialize different users contending for the same inventory. The unique constraints on seat definitions and idempotency records remain database-level safeguards.

## Cancellation

`POST /reservations/{id}/cancel` locks the reservation, verifies ownership, locks its seats in deterministic order, marks the reservation cancelled, and makes its seats available in one transaction. A repeated cancellation is a no-op success. Reservation-seat history remains persisted so a retry of the original idempotency key still identifies the original seats after cancellation.

## API and Errors

- `POST /auth/login`: validates username/password and issues a signed JWT; generic invalid-credential response.
- `POST /shows`: ADMIN JWT required; creates a show and its seats.
- `GET /shows/{id}`: USER or ADMIN JWT required; returns show metadata, seat states, and counts.
- `POST /shows/{id}/reserve`: USER or ADMIN JWT, `Idempotency-Key`, and seat list; identity comes from JWT `sub`.
- `POST /reservations/{id}/cancel`: USER or ADMIN JWT; the service enforces reservation ownership.
- `GET /health/live` and `GET /health/ready`: unauthenticated process liveness and database-backed readiness.
- `GET /metrics`: ADMIN JWT required; returns Prometheus exposition.

New reservations return `201`, identical idempotent replays return `200`, seat/limit/idempotency conflicts return `409`, missing shows return `404`, invalid requests return `400`, unauthenticated or invalid-token requests return `401`, role or ownership failures return `403`, and database access failures return `503`. Error responses include a stable code, safe message, and `request_id`. Only login, health checks, and the static UI allow anonymous access; all business APIs are protected.

The assessment/demo account IDs and passwords are listed in `README.md`. They are seeded for evaluation convenience and must be replaced with managed identities and secrets before production use. The Render Blueprint generates `JWT_SECRET`; local development uses the fallback in `application.yml` unless overridden.

## Persistence and Migrations

Flyway migrations are under `src/main/resources/db/migration`. V1 defines shows, seats, reservations, and per-user coordination locks; later migrations record and backfill reservation-seat history. The Compose database uses a named volume, so local state survives container recreation.

Key constraints include unique seat numbers per show, unique idempotency keys per show/user, valid seat/reservation states, and non-negative paise amounts. The database transaction is the consistency boundary; the application keeps no in-memory inventory copy.

## Observability

- `X-Request-ID` is accepted only when it matches a bounded safe character set; otherwise a UUID is generated. It is returned in the response and included in structured JSON request logs with method, path, status, and duration. Headers and bodies are not logged.
- `reservations_confirmed_total`, `reservations_declined_total` with bounded reason labels, `reservations_cancelled_total`, and `reservations_idempotent_replays_total` count outcomes. `seats_available` is an aggregate database-backed gauge over all shows.
- Readiness checks PostgreSQL; liveness does not.
- Database exceptions are logged with request correlation and returned as a generic `503` response.
- Render logs are available through authenticated dashboard/CLI access, not publicly. No screen recording of a live burst is included.

## Local Verification

Start the service and database:

```sh
docker compose up --build
```

The Docker build runs Maven tests. Check health and metrics:

```sh
curl http://localhost:8080/health/live
curl http://localhost:8080/health/ready
ADMIN_TOKEN=$(curl -fsS -X POST http://localhost:8080/auth/login \
	-H 'Content-Type: application/json' \
	-d '{"username":"admin01","password":"SeatAdmin-2026!01"}' | jq -er '.access_token')
curl -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/metrics
```

Run the reproducible contention check:

```sh
./scripts/burst.sh http://localhost:8080
```

The script verifies one winner for a hot seat, the same-user seat limit, simultaneous same-key retries, key mismatch, missing/malformed auth and idempotency headers, multi-seat all-or-nothing behavior, token-derived identity, owner-only and repeated cancellation, canceled-key replay, a cancel/rebook race, expected HTTP outcomes, and state reconciliation. The expanded implementation has been run locally with 100 concurrent hot-seat requests and the additional edge cases; all verified outcomes preserved the invariants with no 5xx/network failures. This is not evidence of a 20,000-request production run; scale tests against the target deployment remain outstanding.

## Trade-offs and Follow-up

- On 2026-10-04, the hosted readiness endpoint returned `200`, login returned `200`, and an unauthenticated show-creation request returned `401`, confirming that authentication is active on Render. The public endpoints do not expose the deployed commit SHA, so the exact hosted revision has not been independently verified.
- Row locking favors correctness and simplicity; a single hot seat remains a throughput bottleneck.
- The free Render database is temporary and expires on 2026-11-02; the live service is an assessment demo, not a durable production deployment.
- Payment intent is not integrated. A real provider would need provider-side idempotency and a carefully defined transaction boundary.
- The Render-connected source is currently on the `seat-reservation-deploy` branch; the repository default `main` branch must be updated before evaluators can build from a default clone.
- Run the 20,000-request burst against a production-like database, tune transaction/request timeouts, and add an automated database failure integration test before production use.
- Replace seeded assessment accounts with managed identities, add token refresh/revocation and rate limiting, enforce production TLS and secret rotation, and add dashboards, tracing, and alert rules before production use.

## AI Use

GitHub Copilot in VS Code was used throughout implementation and review. It helped draft the React test console, Docker/Render configuration, load and burst scripts, API documentation, and code/tests for request handling, JWT authentication, and observability. The candidate supplied the assessment requirements, chose the deployment platform, and directed revisions to restore admin-only show creation and add role-based JWT access. Generated changes were reviewed and checked with Docker builds, focused tests, local concurrency/edge-case runs, and endpoint checks. A 20,000-request live test has not been run. The candidate should be prepared to explain and extend the locking, idempotency, and transaction design independently.

## Implementation References

- [Reservation transaction and cancellation](src/main/java/com/example/seatreservation/reservations/ReservationService.java)
- [Reservation API](src/main/java/com/example/seatreservation/reservations/ReservationController.java)
- [Show API and persistence](src/main/java/com/example/seatreservation/shows/ShowController.java)
- [Database migrations](src/main/resources/db/migration)
- [Concurrency burst script](scripts/burst.sh)
- [Live service](https://seat-reservation-api-tv2k.onrender.com)
- [Public source branch](https://github.com/KetanBhenwal/Concurrency-Optimizer/tree/seat-reservation-deploy)
