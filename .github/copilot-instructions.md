# Seat Reservation Service

- Use Java 21, Spring Boot, PostgreSQL, JDBC, and Flyway; keep migrations under `src/main/resources/db/migration`.
- Enforce reservation correctness with PostgreSQL transactions and constraints. Acquire seat locks in sorted order and serialize per-show/user limit checks.
- Represent money as integer paise. Derive user identity from the bearer token, never from request JSON.
- Return stable 4xx errors for expected domain conflicts; do not expose database details or credentials.
- Add focused automated tests for changed behavior and run Maven tests or a Docker build when Maven is unavailable locally.
- Keep `README.md` and `WRITEUP.md` aligned with implemented behavior, API contracts, configuration, and verification commands.