Absolutely. Based on the assignment, the MD should function as both an implementation runbook + architecture/design document + deployment/verification checklist, so that your Git history and live service clearly demonstrate correctness rather than just describing it.
Below is a detailed WRITEUP.md structure you can use as the source of truth while implementing. I can also turn this into an actual downloadable .md file once you confirm the stack; for now I’ll keep the design stack-neutral where possible.
Seat Reservation at Scale
Take-Home Exercise — Backend Engineering, Paytm Money
Candidate: <YOUR_NAME>Repository: <GITHUB_REPO_URL>Live URL: <LIVE_URL>Metrics URL: <METRICS_URL>Health URL: <LIVE_URL>/health/liveReadiness URL: <LIVE_URL>/health/ready

1. Executive Summary
This project implements a concurrent seat-reservation service for a high-contention ticketing scenario.
The primary engineering goal is correctness under concurrency.
The service guarantees:
* A seat cannot be confirmed for more than one user.
* Concurrent attempts to reserve the same seat produce exactly one successful reservation and clean 409 Conflict responses for losing requests.
* A user cannot exceed the configured per-show seat limit.
* Retrying a request with the same idempotency key returns the original reservation without creating another reservation or charge.
* Reusing an idempotency key with a different request body is rejected.
* Money is represented exclusively as integer paise.
* Multi-seat reservation semantics are deterministic and atomic.
* Seat counts reconcile with the underlying seat state.
* Domain-level contention does not result in 5xx responses.
* The service exposes health checks, metrics, structured logs, and a reproducible concurrency test.
The implementation intentionally puts the correctness decision inside the database transaction rather than relying on application-level:
read -> check -> write
because that pattern is vulnerable to races under concurrent requests.

2. Problem Statement
At sale time, thousands of users may attempt to reserve seats simultaneously.
A particularly important case is a hot-seat storm:
500 concurrent requests
        |
        v
     A12
        |
        +----> User 1  -> SUCCESS
        +----> User 2  -> DECLINED
        +----> User 3  -> DECLINED
        ...
        +----> User 500 -> DECLINED
The system must guarantee that exactly one request obtains A12.
The system must also correctly handle:
* duplicate/retried requests
* concurrent requests from the same user
* multi-seat reservations
* cancellation/release
* dependency failures
* deployment restarts
* database contention
* observability during high load

3. Goals
Functional Goals
1. Create shows.
2. Create seats for each show.
3. Reserve one or more seats.
4. Enforce per-user reservation limits.
5. Support idempotent reservation requests.
6. Support releasing/cancelling reservations.
7. Return complete show state.
8. Provide health and readiness endpoints.
9. Expose Prometheus-compatible metrics.
10. Provide structured logs.
11. Provide a reproducible concurrency/load test.
Correctness Goals
The following invariants must always hold:
Invariant 1 — No double booking
For every seat:
confirmed_count(seat) <= 1
A seat can belong to at most one active reservation.
Invariant 2 — Seat state reconciliation
For a show:
available + held + confirmed = total_seats
Invariant 3 — Per-user limit
For a show and user:
active_seats(user, show) <= per_user_limit
Invariant 4 — Idempotency
For a (user_id, idempotency_key) pair:
successful reservation count <= 1
Invariant 5 — Identity
The user performing the operation is determined exclusively from authentication.
The request body must never be trusted to determine identity.

4. Non-Goals
The following are intentionally outside the scope of this exercise:
* Building a UI.
* Real payment gateway integration.
* Distributed database deployment.
* Multi-region active-active deployment.
* Sophisticated seat recommendation algorithms.
* Event-driven architecture unless required later.
* Persistent message queues.
* Complex authentication/authorization infrastructure.
The focus is correctness, deployment, and observability.

5. Technology Stack
Application
Language: <LANGUAGE>
Framework: <FRAMEWORK>
Database
Database: <DATABASE>
Recommended implementation:
Application
    |
    v
PostgreSQL
A relational database is used because the correctness requirements map naturally to:
* transactions
* row-level locking
* unique constraints
* conditional updates
* durable state
Deployment
Container: Docker
Platform: <RENDER / RAILWAY / FLY.IO / OTHER>
Observability
* Prometheus-compatible metrics
* Structured JSON logs
* Request/correlation IDs
* Health endpoint
* Readiness endpoint

6. High-Level Architecture
                    ┌─────────────────────┐
                    │      Client         │
                    └──────────┬──────────┘
                               │
                               │ HTTP
                               v
                    ┌─────────────────────┐
                    │   API Application   │
                    │                     │
                    │ Auth Middleware     │
                    │ Request ID          │
                    │ Validation          │
                    │ Reservation Service │
                    └──────────┬──────────┘
                               │
                               │ Transaction
                               v
                    ┌─────────────────────┐
                    │    PostgreSQL       │
                    │                     │
                    │ shows               │
                    │ seats               │
                    │ reservations        │
                    │ idempotency keys    │
                    └─────────────────────┘

                               │
                               ├──────────────> Metrics
                               │
                               └──────────────> Structured Logs
The database is the source of truth for reservation state.

7. Authentication Model
For this exercise, authentication can be implemented using a simple bearer token.
Example:
Authorization: Bearer user-123
The middleware resolves:
token -> user_id
The resulting user_id is attached to the request context.
The reservation endpoint does not accept:
{
  "user_id": "user-123"
}
as an authoritative identity field.
If such a field is included, it is ignored or rejected.
The authenticated identity always comes from the token.

8. API Contract
8.1 Create Show
Request
POST /shows
Content-Type: application/json
Authorization: Bearer admin-token
{
  "name": "friday-night",
  "seats": [
    "A1",
    "A2",
    "A3",
    "A4"
  ],
  "price_paise": 25000,
  "per_user_limit": 4
}
per_user_limit defaults to 4.
Response
201 Created
{
  "id": "show-123",
  "name": "friday-night",
  "price_paise": 25000,
  "per_user_limit": 4,
  "seats": [
    {
      "seat": "A1",
      "status": "available"
    },
    {
      "seat": "A2",
      "status": "available"
    }
  ]
}

9. Reserve Seat
Request
POST /shows/{show_id}/reserve
Authorization: Bearer user-123
Content-Type: application/json
Idempotency-Key: abc-123
{
  "seats": [
    "A12"
  ]
}
The preferred implementation stores the idempotency key in the HTTP header.
The key may also be accepted from the request body if required by the implementation, but one canonical mechanism should be documented and tested.

10. Successful Reservation
201 Created
{
  "reservation_id": "reservation-123",
  "show_id": "show-123",
  "user_id": "user-123",
  "seats": [
    "A12"
  ],
  "amount_paise": 25000,
  "status": "confirmed"
}

11. Reservation Failure Semantics
Domain-level declines must use 4xx responses.
They must not become 500 Internal Server Error.
Recommended mapping:
Condition	HTTP
Seat already taken	409
Per-user limit exceeded	409
Idempotency key reused with different body	409
Reservation not owned by user	403
Show not found	404
Seat does not exist	400/404
Invalid request	400
Database unavailable	503
Unexpected application failure	500
The exact API error structure should be consistent.
Example:
{
  "error": {
    "code": "SEAT_TAKEN",
    "message": "One or more requested seats are already unavailable"
  },
  "request_id": "req-123"
}

12. Partial Reservation Semantics
This implementation uses:
All-or-Nothing Semantics
If the user requests:
{
  "seats": ["A12", "A13"]
}
then either:
A12 + A13
are both successfully reserved, or neither is reserved.
There is no partial success.
This makes the operation easier to reason about and prevents surprising states where a user receives only part of a requested reservation.
Example:
A12 = available
A13 = confirmed

Request:
[A12, A13]

Result:
409

A12 remains available.
No partial mutation occurs.

13. Database Schema
The recommended schema is:
shows
-----
id
name
price_paise
per_user_limit
created_at
seats
-----
id
show_id
seat_number
status
created_at
updated_at
reservations
------------
id
show_id
user_id
status
amount_paise
idempotency_key
request_fingerprint
created_at
updated_at
reservation_seats
-----------------
reservation_id
seat_id

14. Database Constraints
The database must enforce important correctness properties.
Unique seat within show
UNIQUE(show_id, seat_number)
This prevents duplicate seat definitions.
Idempotency uniqueness
UNIQUE(user_id, show_id, idempotency_key)
This prevents two reservation records from being created for the same idempotency key.
Reservation-seat uniqueness
UNIQUE(seat_id)
This should be applied only where appropriate to active/confirmed reservation ownership.
If the database supports partial unique indexes, use one for active reservation states.
Example conceptually:
CREATE UNIQUE INDEX ...
ON reservation_seats(seat_id)
WHERE reservation_is_active = true;
Alternatively, seat ownership can be represented directly in the seats table, which can simplify correctness.

15. Seat State Machine
Each seat has one of:
AVAILABLE
HELD
CONFIRMED
Transitions:
AVAILABLE
    |
    v
  HELD
    |
    v
CONFIRMED
Cancellation/release:
HELD --------> AVAILABLE
A confirmed reservation should not silently return to available unless the business model explicitly permits confirmed cancellation.
For this implementation, cancellation is defined explicitly and must be transactional.

16. Reservation Transaction
The reservation decision must happen inside one database transaction.
Conceptually:
BEGIN

1. Validate show

2. Validate idempotency key

3. Lock requested seat rows
   in deterministic order

4. Check all requested seats are available

5. Count user's active seats

6. Validate per-user limit

7. Create reservation

8. Change seat state

9. Create reservation-seat relationships

10. Commit

COMMIT
Any failure results in:
ROLLBACK
No partial state is committed.

17. Why Read-Then-Write Is Incorrect
An unsafe implementation looks like:
SELECT status FROM seats WHERE seat = 'A12';

if status == AVAILABLE:
    UPDATE seats
    SET status = CONFIRMED
Two requests can execute:
Request A                 Request B

SELECT A12
AVAILABLE                 AVAILABLE

UPDATE A12
CONFIRMED

                          UPDATE A12
                          CONFIRMED
Both requests observed the same state.
Therefore the application-level check is not sufficient.

18. Atomic Concurrency Strategy
The implementation uses database row locks.
For a reservation containing:
["A13", "A12", "A14"]
the service sorts the seat identifiers:
["A12", "A13", "A14"]
and locks them in that deterministic order.
Conceptually:
SELECT *
FROM seats
WHERE show_id = $1
AND seat_number IN (...)
ORDER BY seat_number
FOR UPDATE;
The transaction then verifies that every selected seat is still available.
Because competing transactions must wait for the same row lock, only one transaction can make the state transition first.
Example:
Transaction A                 Transaction B

LOCK A12                      LOCK A12
    |                              |
    v                              |
AVAILABLE                          waits
    |
    v
CONFIRMED
    |
COMMIT
                                   |
                                   v
                              reads CONFIRMED
                                   |
                                   v
                              409 SEAT_TAKEN
This is the critical correctness mechanism.

19. Multi-Seat Deadlock Prevention
Multiple seats create a possible deadlock if transactions lock rows in different orders.
Unsafe:
Transaction A:
lock A12
lock A13

Transaction B:
lock A13
lock A12
This can create:
A waits for B
B waits for A
To prevent this, every transaction locks seats in the same deterministic order.
For example:
sort(seat_numbers)
then lock:
A12
A13
A14
This creates a consistent lock acquisition order.

20. Per-User Limit
The per-user limit is checked inside the same transaction that locks the requested seats.
For example:
limit = 4

existing active seats = 3
requested seats = 2

3 + 2 > 4

=> reject entire request
This must not be implemented as:
SELECT count(...)
outside transaction

then later INSERT
because multiple concurrent requests could each observe the same count.
The count and reservation decision must be part of the same transaction/locking strategy.

21. Per-User Concurrency
Example:
User U
limit = 4

10 concurrent requests
each requests 1 seat
The final state must satisfy:
active seats <= 4
The implementation serializes the relevant user/show decision sufficiently to make this check atomic.
A practical approach is to lock the show/user coordination row or otherwise serialize reservation decisions for the same (show_id, user_id) pair.
The exact mechanism used by the implementation should be documented here:
Implementation: <DESCRIBE ACTUAL MECHANISM>
Example:
A dedicated user_show_limits row is locked using SELECT ... FOR UPDATE.

22. Idempotency
Idempotency is required because clients may retry requests after:
* network timeout
* connection reset
* load balancer retry
* client timeout
* mobile connectivity changes
Example:
POST /shows/123/reserve

Idempotency-Key: abc
First request:
201 Created
reservation_id = R1
Retry:
Idempotency-Key: abc
must return:
R1
and must not create:
R2

23. Idempotency Storage
The database stores:
user_id
show_id
idempotency_key
request_fingerprint
reservation_id
with:
UNIQUE(user_id, show_id, idempotency_key)
The request fingerprint is generated from the canonical request content.
For example:
sorted requested seats
+
show_id
A hash can then be stored:
SHA-256(canonical_request)

24. Same-Key / Different-Body
This case must be explicitly handled.
First request:
Idempotency-Key: abc

{
  "seats": ["A12"]
}
Second request:
Idempotency-Key: abc

{
  "seats": ["A13"]
}
The second request must return:
409 Conflict
with:
{
  "error": {
    "code": "IDEMPOTENCY_KEY_REUSED",
    "message": "The idempotency key was already used with a different request"
  }
}
It must not modify the original reservation.

25. Idempotency Race
Two identical requests can arrive simultaneously:
Request A
Idempotency-Key: abc

Request B
Idempotency-Key: abc
The implementation must not rely only on:
SELECT key
followed by:
INSERT key
because both requests could observe that the key does not exist.
The database unique constraint is the final authority.
Conceptually:
Request A -> INSERT idempotency record -> success
Request B -> INSERT idempotency record -> unique constraint conflict
The second request then reads the existing record and returns the original result.

26. Cancellation / Release
This implementation uses:
Explicit Cancellation
POST /reservations/{id}/cancel
Authorization: Bearer user-123
Only the reservation owner may cancel it.
The cancellation happens transactionally.
Conceptually:
BEGIN

lock reservation

verify owner

lock associated seats

verify reservation is cancellable

mark reservation cancelled

mark seats available

COMMIT
This prevents cancellation from racing with another reservation.

27. Cancellation Race
Consider:
Reservation R owns A12
At the same time:
User A -> cancel R
User B -> reserve A12
The two operations must serialize through database locking.
Possible ordering:
Cancel transaction
    |
    v
lock A12
    |
    v
mark AVAILABLE
    |
    v
COMMIT
    |
    v
Reserve transaction acquires lock
    |
    v
marks CONFIRMED
or:
Reserve transaction
    |
    v
lock A12
    |
    v
sees CONFIRMED
    |
    v
409
There must never be a state where both users own A12.

28. Show State API
GET /shows/{id}
Example response:
{
  "id": "show-123",
  "name": "friday-night",
  "price_paise": 25000,
  "counts": {
    "total": 100,
    "available": 96,
    "held": 0,
    "confirmed": 4
  },
  "seats": [
    {
      "seat": "A1",
      "status": "available"
    },
    {
      "seat": "A2",
      "status": "confirmed"
    }
  ]
}
The response is also used by the burst test to verify reconciliation.

29. Reconciliation Invariant
For every response:
available + held + confirmed == total
Example:
available = 96
held       = 0
confirmed  = 4
total      = 100
Therefore:
96 + 0 + 4 = 100
The implementation should also include an internal reconciliation check during testing.

30. Health Checks
Liveness
GET /health/live
Purpose:
Is the application process alive?
This endpoint should not depend on the database.
Expected:
200 OK
{
  "status": "ok"
}

31. Readiness
GET /health/ready
Readiness checks actual dependencies.
For example:
Application
     |
     v
Database ping
     |
     +---- reachable -> 200
     |
     +---- unavailable -> 503
Example failure:
503 Service Unavailable
{
  "status": "not_ready",
  "dependencies": {
    "database": "unavailable"
  }
}
The service must fail closed rather than reporting readiness when the database is unavailable.

32. Metrics
Prometheus-style metrics are exposed through:
GET /metrics
Minimum required metrics:
reservations_confirmed_total
reservations_declined_total
seats_available
Declines must have a reason label.
Example:
reservations_declined_total{reason="seat_taken"}
reservations_declined_total{reason="per_user_limit"}
reservations_declined_total{reason="idempotent_replay"}
Additional useful metrics:
reservation_requests_total
reservation_duration_seconds
reservation_errors_total
database_errors_total
http_requests_total
http_request_duration_seconds

33. Metrics Cardinality
Do not put unbounded values such as:
user_id
reservation_id
request_id
into Prometheus labels.
Good:
reason="seat_taken"
Bad:
user_id="user-938292"
This prevents uncontrolled metric cardinality.

34. Metrics Reconciliation
The service should be able to compare:
API state
with:
metrics
For example:
GET /shows/{id}

confirmed = 37
and:
reservations_confirmed_total
should reflect the same successful reservation activity, subject to the metric's documented scope.
If metrics are process-local, this limitation should be documented.

35. Structured Logging
Logs should be JSON structured.
Example:
{
  "timestamp": "2026-10-01T10:00:00Z",
  "level": "INFO",
  "message": "reservation completed",
  "request_id": "req-123",
  "show_id": "show-123",
  "reservation_id": "res-456",
  "user_id": "user-123",
  "seat_count": 1,
  "amount_paise": 25000,
  "duration_ms": 12
}

36. Request Correlation
Every request receives a request ID.
If the client provides:
X-Request-ID: abc
the service can preserve it after validating it.
Otherwise:
generate UUID
The request ID should appear in:
* response headers
* application logs
* error responses
Example:
X-Request-ID: req-123

37. Sensitive Logging
Do not log:
* authentication tokens
* secrets
* passwords
* full authorization headers
* unnecessary personal data
User identifiers should only be logged where operationally useful and appropriate.

38. Error Handling
The API should distinguish between:
Expected domain errors
Examples:
SEAT_TAKEN
PER_USER_LIMIT_EXCEEDED
IDEMPOTENCY_KEY_REUSED
RESERVATION_NOT_OWNED
These return 4xx.
Infrastructure errors
Examples:
DATABASE_UNAVAILABLE
DATABASE_TIMEOUT
These return 503.
Unexpected errors
These return:
500 Internal Server Error
However, a correctly implemented hot-seat race should never result in 500.

39. Transaction Error Handling
Database errors must be handled carefully.
If a transaction fails:
ROLLBACK
must occur before the connection is reused.
Unique constraint violations should be translated into the appropriate domain result where expected.
Unexpected database errors should be logged with:
request_id
error
operation
but must not leak database internals to the client.

40. Dockerization
The repository should contain:
Dockerfile
docker-compose.yml
.dockerignore
A clean checkout should support:
docker compose up --build
and result in:
Application
    |
    v
PostgreSQL
running locally.

41. Configuration
Configuration should come from environment variables.
Example:
DATABASE_URL
PORT
LOG_LEVEL
ADMIN_TOKEN
PER_USER_LIMIT
No secrets should be committed to Git.
Provide:
.env.example
Example:
DATABASE_URL=postgres://...
PORT=8080
LOG_LEVEL=info
ADMIN_TOKEN=change-me

42. Database Migrations
Database schema changes must be represented as migrations.
Example:
migrations/
    001_create_shows.sql
    002_create_seats.sql
    003_create_reservations.sql
    004_create_idempotency.sql
A clean checkout should be able to initialize the database without manual schema editing.

43. Repository Structure
Recommended structure:
.
├── cmd/
├── internal/
│   ├── auth/
│   ├── shows/
│   ├── reservations/
│   ├── database/
│   ├── metrics/
│   └── logging/
├── migrations/
├── tests/
├── scripts/
│   └── burst.sh
├── Dockerfile
├── docker-compose.yml
├── Makefile
├── README.md
├── WRITEUP.md
├── .env.example
└── .gitignore
Adapt this to the selected language/framework.

44. Local Development
Prerequisites:
Docker
Docker Compose
Git
Clone:
git clone <REPO_URL>
cd <REPO_NAME>
Start:
docker compose up --build
Verify:
curl http://localhost:8080/health/live
Expected:
{
  "status": "ok"
}
Verify readiness:
curl http://localhost:8080/health/ready

45. Creating a Test Show
Example:
curl -X POST http://localhost:8080/shows \
  -H 'Authorization: Bearer admin-token' \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "load-test",
    "seats": ["A1", "A2", "A3", "A4", "A5"],
    "price_paise": 25000,
    "per_user_limit": 4
  }'
Save the returned show ID.

46. Manual Reservation
curl -X POST \
  http://localhost:8080/shows/<SHOW_ID>/reserve \
  -H 'Authorization: Bearer user-1' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: test-1' \
  -d '{
    "seats": ["A1"]
  }'
Expected:
201 Created

47. Idempotency Test
Repeat the exact request:
curl -X POST \
  http://localhost:8080/shows/<SHOW_ID>/reserve \
  -H 'Authorization: Bearer user-1' \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: test-1' \
  -d '{
    "seats": ["A1"]
  }'
Expected:
same reservation
No additional seat should be created.

48. Same-Key-Different-Body Test
Use:
Idempotency-Key: test-1
but request:
{
  "seats": ["A2"]
}
Expected:
409 Conflict
The existing reservation remains unchanged.

49. Hot-Seat Concurrency Test
The burst test must create many users targeting one seat.
Example:
500 concurrent users
        |
        v
      A12
Expected:
201 = 1
409 = 499
5xx = 0
The exact test size can be increased to approximately:
20,000 requests
for the final validation.

50. Per-User Concurrency Test
Create a show with:
per_user_limit = 4
Then send:
10 concurrent requests
from:
user-1
Each request attempts to reserve one seat.
Expected:
active seats <= 4
No matter how the requests interleave.

51. Mixed Concurrency Test
The production-style burst should combine:
* hot-seat contention
* multiple users
* repeated idempotency keys
* different seats
* per-user concurrency
* invalid requests
* already-taken seats
The test must classify every response.
Example output:
Total requests:       20000

201 confirmed:            42
409 seat taken:        15000
409 user limit:         2000
409 idempotent replay:  2958

4xx other:                 0
5xx:                       0

Final seats:
total:                  100
available:               58
held:                     0
confirmed:               42

Reconciliation:
58 + 0 + 42 = 100

PASS
The numbers above are illustrative only.

52. Burst Script
The repository must include:
scripts/burst.sh
or an equivalent program.
Usage:
./scripts/burst.sh <BASE_URL>
The script should:
1. Create a fresh show.
2. Generate unique users.
3. Generate concurrent requests.
4. Generate hot-seat contention.
5. Generate retries.
6. Collect HTTP responses.
7. Count results by category.
8. Fetch final show state.
9. Verify reconciliation.
10. Exit non-zero if an invariant fails.
Example:
Running concurrency test...

Requests: 20000
Concurrency: 500

Hot seat: A12

Results:
  confirmed: 1
  seat_taken: 499
  per_user_limit: 0
  idempotent_replay: 0
  5xx: 0

Final state:
  total: 100
  available: 99
  held: 0
  confirmed: 1

Invariant:
  99 + 0 + 1 = 100

PASS

53. Test Exit Codes
The burst script should return:
0
when all invariants pass.
It should return non-zero if:
* any unexpected 5xx occurs
* more than one user obtains the same seat
* reconciliation fails
* per-user limit is violated
* idempotency creates multiple reservations
* final state is inconsistent
This makes it suitable for CI.

54. Automated Tests
The test suite should include at least:
Unit tests
* request validation
* amount calculation
* request fingerprint generation
* error mapping
* authentication extraction
Integration tests
* show creation
* reservation
* cancellation
* idempotency
* per-user limits
* database constraints
Concurrency tests
* same seat
* multiple seats
* same user
* same idempotency key
* cancellation vs reservation

55. Concurrency Test Matrix
Test	Expected
100 users -> same seat	1 success
1000 users -> same seat	1 success
20k users -> same seat	1 success
same user -> 10 seats, limit 4	max 4
same key -> same body	one reservation
same key -> different body	409
two users -> same two seats	one complete reservation
cancel + reserve race	no double ownership
invalid seat	4xx
DB unavailable	readiness 503
56. Money Handling
Money is represented in integer paise.
Example:
₹250.00
is represented as:
25000
Never use:
float
double
for monetary values.
Database column:
BIGINT / INTEGER
depending on the expected range.
Example:
price_paise = 25000
For a three-seat reservation:
3 * 25000 = 75000

57. Payment / Double-Charge Model
The exercise requires that retries do not double-charge.
Since a real payment provider is outside scope, the reservation itself acts as the durable idempotent business operation.
The key property is:
one successful reservation
=
one successful charge intent
If a payment provider were introduced, the same idempotency key would need to propagate to the payment provider.
The architecture would become:
Client
  |
  v
Reservation Service
  |
  +---- Database transaction
  |
  +---- Payment provider
          |
          v
      idempotency key
A real implementation would need to carefully define the transaction boundary between seat allocation and payment authorization.

58. Consistency vs Availability
The service prioritizes correctness of seat ownership over availability during database failures or ambiguous transaction outcomes.
The reason is that returning success when the database state cannot be durably committed could result in:
client believes seat was sold
database believes seat is available
which can eventually lead to double-selling.
Therefore:
database unavailable
        |
        v
503 Service Unavailable
rather than optimistic reservation success.

59. Partition Behaviour
The database is the source of truth.
If the service cannot reach the database:
readiness = false
reservation = unavailable
The service does not maintain an independent in-memory seat state that could diverge from the database.
This intentionally chooses consistency over availability during a database partition.

60. Failure Scenarios
Database unavailable
Expected:
GET /health/live  -> 200
GET /health/ready -> 503
POST /reserve     -> 503
Application restart
Expected:
* reservations remain persisted
* seat ownership remains persisted
* idempotency records remain persisted
Duplicate request after restart
Expected:
original reservation returned
because idempotency state is stored durably.

61. Observability During the Burst
During the load test, monitor:
request rate
latency
5xx rate
reservation success rate
seat-taken rate
database latency
database connection usage
The most important operational signals are:
5xx rate
database errors
reservation latency
and correctness signals:
confirmed reservations
seat availability
reconciliation

62. 2AM Alerts
The following conditions would warrant investigation:
Critical
5xx rate > 0 during reservation burst
Critical
reconciliation invariant violated
Critical
database unavailable
Warning
reservation latency significantly above baseline
Warning
database connection pool exhaustion
Warning
unexpected increase in deadlocks/timeouts

63. Metrics to Alert On
Example alerts:
rate(http_requests_total{status=~"5.."}) > threshold
database_errors_total increasing
reservation_duration_seconds high percentile increasing
reconciliation failures > 0
For a production deployment, alerts should include links to dashboards and relevant runbooks.

64. Deployment
The service is containerized.
Example:
docker build -t seat-reservation .
Run:
docker run \
  -e DATABASE_URL="$DATABASE_URL" \
  -p 8080:8080 \
  seat-reservation
The production platform is:
<PLATFORM>
Deployment URL:
<LIVE_URL>

65. Production Configuration Checklist
Before deployment:
* Database provisioned
* Environment variables configured
* Migrations executed
* Health endpoint configured
* Readiness endpoint configured
* Port configuration verified
* Docker image builds
* Startup command verified
* Logs visible
* Metrics endpoint reachable
* No secrets committed
* Cold start tested

66. Cold Start Verification
The live deployment must be tested from a clean state.
Procedure:
1. Deploy
2. Wait for instance startup
3. Call /health/live
4. Call /health/ready
5. Create show
6. Reserve seat
7. Retry reservation
8. Fetch show
All steps must work after a cold start.

67. Clean Checkout Verification
The repository must work from scratch.
Test:
git clone <REPO_URL>
cd <REPO_NAME>

docker compose up --build
Then:
curl http://localhost:8080/health/live
The result must not depend on local uncommitted files.

68. Git Commit Strategy
The assignment explicitly asks for full commit history.
Development should therefore be committed incrementally.
Suggested sequence:
1. Initialize project
2. Add HTTP server
3. Add database configuration
4. Add schema/migrations
5. Add show creation
6. Add seat model
7. Add authentication
8. Add reservation transaction
9. Add idempotency
10. Add per-user concurrency protection
11. Add cancellation
12. Add health endpoints
13. Add metrics
14. Add structured logging
15. Add tests
16. Add burst test
17. Add Docker
18. Add deployment configuration
19. Fix load-test issues
20. Final documentation
Avoid one giant final commit.

69. AI Usage
AI tools were used as development assistants.
The goal was not to outsource architectural responsibility.
AI assistance included:
* generating initial boilerplate
* suggesting API structures
* reviewing concurrency approaches
* generating test cases
* identifying edge cases
* improving Docker configuration
* generating load-test scaffolding
* reviewing documentation
* suggesting observability metrics
Human decisions included:
* database choice
* transaction boundaries
* seat locking strategy
* idempotency semantics
* all-or-nothing reservation semantics
* per-user concurrency strategy
* consistency/availability trade-off
* deployment architecture
* interpreting concurrency test results
All generated code was reviewed, tested, and modified as necessary.

70. AI Prompting Approach
Examples of useful AI prompts:
Review this reservation transaction for race conditions.
Assume 20,000 concurrent requests can target the same seat.
Identify every possible double-booking scenario.
Review this PostgreSQL locking strategy for multi-seat reservations.
Can two transactions deadlock?
Generate concurrency tests for:
1. same seat
2. same user
3. same idempotency key
4. cancellation race
Review these Prometheus metrics for cardinality problems.
AI output was treated as a proposal rather than an authority.

71. What I Would Do Next
If this were extended into a production ticketing platform, the next improvements would include:
Authentication
Use a proper identity provider/JWT validation rather than exercise-level bearer tokens.
Payment
Integrate a real payment provider using provider-side idempotency keys.
Distributed Deployment
Run multiple application instances behind a load balancer while retaining the database as the consistency authority.
Database Scaling
Evaluate:
* connection pooling
* read replicas
* partitioning
* indexing
* hot-row contention
Queueing
For extremely high traffic, introduce controlled admission or queueing before hitting the reservation transaction.
Observability
Add:
* distributed tracing
* dashboards
* SLOs
* automated alerts
* database performance dashboards
Rate Limiting
Protect the reservation endpoint from abusive clients.

72. Security Considerations
The service should:
* authenticate users
* authorize cancellation
* validate all input
* never trust body-provided identity
* avoid SQL injection
* avoid logging secrets
* validate idempotency keys
* enforce request size limits
* apply rate limiting in production
* use HTTPS
* protect admin endpoints

73. Performance Considerations
The main contention point is intentionally the seat row.
For a hot seat:
A12
many transactions may wait for the same row lock.
This is expected.
The important requirement is that contention results in:
one successful reservation
many clean declines
rather than:
double booking
deadlock
500 errors
Performance should therefore be evaluated together with correctness.

74. Hot Seat Optimization
If the system reaches extremely high scale, repeatedly locking a single hot seat may become a throughput bottleneck.
Possible future approaches:
* admission control
* request queueing
* partitioned inventory
* distributed reservation queues
* dedicated inventory workers
However, these add complexity.
For this assignment, database-level transactional correctness is preferred because the system is small and correctness is the primary requirement.

75. Database Indexing
Indexes should support:
shows.id
seats(show_id, seat_number)
reservations(show_id, user_id)
reservations(show_id, idempotency_key)
reservation_seats(seat_id)
Indexes should be validated against actual query plans.
Avoid blindly adding indexes because every index also increases write cost.

76. Deadlock Handling
Even with deterministic locking, deadlocks can still occur due to unrelated database operations.
The application should:
1. Detect transient transaction failures.
2. Roll back the transaction.
3. Retry only when the failure is safely retryable.
4. Use bounded retry attempts.
5. Preserve idempotency semantics.
Example:
max retries = 2 or 3
Retries must never cause duplicate reservations.

77. Request Timeout
Reservation transactions should have bounded timeouts.
A request stuck waiting indefinitely for a database lock is undesirable.
The implementation should configure:
HTTP timeout
DB query timeout
transaction timeout
appropriately.

78. Testing the Reconciliation Invariant
After every concurrency test:
GET /shows/{id}
and calculate:
available + held + confirmed
Compare against:
total
If unequal:
FAIL
The burst test must exit with a non-zero status.

79. Final Acceptance Checklist
Functional
* POST /shows
* POST /shows/{id}/reserve
* POST /reservations/{id}/cancel
* GET /shows/{id}
* Authentication implemented
* Token-derived identity
* Integer paise
* Per-user limit
* Idempotency
* Same-key/different-body rejection
* All-or-nothing multi-seat reservation
Correctness
* No double booking
* Hot-seat concurrency tested
* Per-user concurrency tested
* Idempotency race tested
* Cancellation race tested
* Multi-seat deadlock prevention
* Reconciliation invariant tested
* No expected 5xx responses
Deployment
* Dockerfile
* Docker Compose
* Public URL
* Cold start verified
* Clean checkout verified
* Database migrations
* Environment variables
* Secrets excluded from Git
Observability
* Liveness
* Readiness
* Prometheus metrics
* Reservation counters
* Decline reason metrics
* Available seats gauge
* Structured logs
* Request ID
* Burst output
* Operational alerts documented
Documentation
* README
* WRITEUP
* API examples
* Local setup
* Deployment instructions
* Burst test instructions
* Architecture explanation
* AI usage disclosure
* Trade-offs documented

80. Final Verification
Before submitting, perform the following exact sequence.
Step 1 — Fresh clone
git clone <REPO_URL>
cd <REPO_NAME>
Step 2 — Build
docker compose build
Step 3 — Start
docker compose up
Step 4 — Health
curl http://localhost:8080/health/live
curl http://localhost:8080/health/ready
Step 5 — Run tests
make test
Step 6 — Run race/concurrency tests
make burst
Step 7 — Verify metrics
curl http://localhost:8080/metrics
Step 8 — Deploy
Deploy the exact container/image that passed local verification.
Step 9 — Run burst against production
./scripts/burst.sh <LIVE_URL>
Step 10 — Verify final state
Confirm:
available + held + confirmed = total
Step 11 — Verify Git
git status
git log --oneline --decorate --graph
Ensure:
working tree clean
and the history demonstrates incremental development.

81. Submission Information
Repository
<REPOSITORY_URL>
Live Service
<LIVE_URL>
Health
<LIVE_URL>/health/live
Readiness
<LIVE_URL>/health/ready
Metrics
<LIVE_URL>/metrics
Burst Test
./scripts/burst.sh <LIVE_URL>
Documentation
README.md
WRITEUP.md

82. Final Design Summary
The central correctness decision is deliberately delegated to the database transaction.
For a reservation:
Request
   |
   v
Authenticate user
   |
   v
Validate request
   |
   v
BEGIN TRANSACTION
   |
   v
Lock seats in deterministic order
   |
   v
Check idempotency
   |
   v
Check seat availability
   |
   v
Check per-user limit
   |
   v
Create reservation
   |
   v
Mark seats confirmed
   |
   v
COMMIT
   |
   v
201 Created
For competing requests:
             A12
              |
      ┌───────┴────────┐
      │                │
 Request A          Request B
      │                │
      v                v
  row lock          waits
      │                │
      v                │
 CONFIRMED             │
      │                │
   COMMIT              │
                       v
                 reads CONFIRMED
                       │
                       v
                      409
The database therefore remains the system of record for seat ownership.
The application layer provides:
* authentication
* validation
* transaction orchestration
* API semantics
* observability
* deployment
* testing
while the database provides the atomicity and durability required to guarantee correctness under contention.

Appendix A — Important Invariants
These should be treated as executable properties wherever practical.
NO_DOUBLE_SELL
---------------
A seat can belong to at most one active reservation.


USER_LIMIT
----------
active_seats(show, user) <= per_user_limit


RECONCILIATION
--------------
available + held + confirmed = total


IDEMPOTENCY
-----------
same(user, show, key) -> same reservation


IDENTITY
--------
request.user_id = authenticated_token.user_id


MONEY
-----
all monetary values are integer paise

Appendix B — Example Error Contract
All errors should follow one consistent format:
{
  "error": {
    "code": "SEAT_TAKEN",
    "message": "Requested seat is already unavailable"
  },
  "request_id": "req-123"
}
Recommended error codes:
INVALID_REQUEST
UNAUTHORIZED
FORBIDDEN
SHOW_NOT_FOUND
SEAT_NOT_FOUND
SEAT_TAKEN
PER_USER_LIMIT_EXCEEDED
IDEMPOTENCY_KEY_REQUIRED
IDEMPOTENCY_KEY_REUSED
RESERVATION_NOT_FOUND
RESERVATION_NOT_OWNED
RESERVATION_NOT_CANCELLABLE
DATABASE_UNAVAILABLE
INTERNAL_ERROR

Appendix C — Interview Discussion Topics
Be prepared to explain without referring to the document:
1. Why is read-then-write unsafe?
2. Where exactly does atomicity come from?
3. Why are row locks necessary?
4. Why must multi-seat locks use deterministic ordering?
5. How does idempotency work under concurrent duplicate requests?
6. Why is the database unique constraint important?
7. How is the per-user limit protected from concurrent requests?
8. What happens when PostgreSQL is unavailable?
9. Why does readiness check the database but liveness does not?
10. How would this architecture behave with 20,000 simultaneous requests?
11. What is the hottest database row?
12. How would you scale beyond a single database?
13. How would payment integration change the transaction boundary?
14. What happens if the process crashes immediately after committing?
15. What happens if the client times out but the reservation actually succeeded?
16. How do you prove that no double booking occurred?
17. What metrics would you watch during the sale?
18. What would page you at 2AM?
19. What parts of the implementation were assisted by AI?
20. What architectural decisions did you make yourself?

Appendix D — Submission Rule
Before submission, replace every placeholder:
<YOUR_NAME>
<REPO_URL>
<LIVE_URL>
<PLATFORM>
<LANGUAGE>
<FRAMEWORK>
<DATABASE>
with the actual implementation details.
The documentation must describe the actual running system, not an idealized architecture.
If the implementation differs from this document, update the document to match the code before submitting.