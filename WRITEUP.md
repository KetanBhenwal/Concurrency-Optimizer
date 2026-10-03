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
- User identity for reservations and cancellations comes from the bearer token, not JSON.

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

- `POST /shows`: admin bearer token; creates a show and its seats.
- `GET /shows/{id}`: returns show metadata, seat states, and counts.
- `POST /shows/{id}/reserve`: user bearer token, `Idempotency-Key`, and seat list.
- `POST /reservations/{id}/cancel`: reservation owner only.
- `GET /health/live`: process liveness, independent of the database.
- `GET /health/ready`: database-backed readiness.
- `GET /metrics`: Prometheus exposition.

New reservations return `201`, identical idempotent replays return `200`, seat/limit/idempotency conflicts return `409`, missing shows return `404`, invalid requests return `400`, unauthenticated requests return `401`, non-owner cancellation returns `403`, and database access failures return `503`. Error responses include a stable code, safe message, and `request_id`.

The exercise authentication maps a bearer token directly to a user ID and compares the admin token from configuration. It is intentionally not a production identity system. Configure strong secrets before deployment.

## Persistence and Migrations

Flyway migrations are under `src/main/resources/db/migration`. V1 defines shows, seats, reservations, and per-user coordination locks; later migrations record and backfill reservation-seat history. The Compose database uses a named volume, so local state survives container recreation.

Key constraints include unique seat numbers per show, unique idempotency keys per show/user, valid seat/reservation states, and non-negative paise amounts. The database transaction is the consistency boundary; the application keeps no in-memory inventory copy.

## Observability

- `X-Request-ID` is accepted only when it matches a bounded safe character set; otherwise a UUID is generated. It is returned in the response and stored in logging MDC.
- Reservation, decline-reason, cancellation, and idempotent-replay counters use bounded metric labels.
- Readiness checks PostgreSQL; liveness does not.
- Database exceptions are logged with request correlation and returned as a generic `503` response.

## Local Verification

Start the service and database:

```sh
docker compose up --build
```

The Docker build runs Maven tests. Check health and metrics:

```sh
curl http://localhost:8080/health/live
curl http://localhost:8080/health/ready
curl http://localhost:8080/metrics
```

Run the reproducible contention check:

```sh
./scripts/burst.sh http://localhost:8080
```

The script verifies one winner for a hot seat, the same-user seat limit, simultaneous same-key retries, key mismatch, expected HTTP outcomes, and state reconciliation. The implementation has been run locally with 100 concurrent hot-seat requests, 10 concurrent requests from one limited user, and 10 simultaneous identical-key requests; all preserved the expected invariants with no 5xx/network failures. This is not evidence of a 20,000-request production run; scale tests against the target deployment remain outstanding.

## Trade-offs and Follow-up

- Row locking favors correctness and simplicity; a single hot seat remains a throughput bottleneck.
- Payment intent is not integrated. A real provider would need provider-side idempotency and a carefully defined transaction boundary.
- Add integration tests for cancellation races and database failure behavior, tune transaction/request timeouts, and run larger bursts against a production-like database before deployment.
- Replace exercise bearer tokens with verified JWT/OIDC identities, add rate limiting, TLS enforcement, structured JSON logging, dashboards, tracing, and alert rules before production use.
- Deployment URL, repository URL, candidate name, and AI-use disclosure must be added by the submitter when known.

## Implementation References

- [Reservation transaction and cancellation](src/main/java/com/example/seatreservation/reservations/ReservationService.java)
- [Reservation API](src/main/java/com/example/seatreservation/reservations/ReservationController.java)
- [Show API and persistence](src/main/java/com/example/seatreservation/shows/ShowController.java)
- [Database migrations](src/main/resources/db/migration)
- [Concurrency burst script](scripts/burst.sh)
