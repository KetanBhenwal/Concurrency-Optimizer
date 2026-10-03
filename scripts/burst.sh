   #!/usr/bin/env bash
   set -euo pipefail
   PATH="${PATH:-}:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
   export PATH

   if [[ "${1:-}" == "__request" ]]; then
      phase=$2
      number=$3
      access_token=$4
      key=$5
      seat=$6
      response_file="$WORK_DIR/$phase-$number.json"
      status=$(curl -sS -o "$response_file" -w '%{http_code}' \
         -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
         -H "Authorization: Bearer $access_token" \
         -H "Idempotency-Key: $key" \
         -H 'Content-Type: application/json' \
         -d "{\"seats\":[\"$seat\"]}") || status=000
      printf '%s\n' "$status" > "$WORK_DIR/$phase-$number.status"
      exit 0
   fi

   BASE_URL=${1:?Usage: scripts/burst.sh <BASE_URL>}
   BASE_URL=${BASE_URL%/}
   REQUESTS=${REQUESTS:-100}
   CONCURRENCY=${CONCURRENCY:-50}
   WORK_DIR=$(mktemp -d)
   trap 'rm -rf "$WORK_DIR"' EXIT
   export BASE_URL WORK_DIR

   for tool in curl jq xargs seq; do
      command -v "$tool" >/dev/null || { printf 'Missing required command: %s\n' "$tool" >&2; exit 1; }
   done

   if ! curl --retry 30 --retry-all-errors --retry-delay 1 --retry-max-time 30 \
      -fsS "$BASE_URL/health/ready" >/dev/null; then
      printf 'Service did not become ready: %s\n' "$BASE_URL" >&2
      exit 1
   fi

   login() {
      curl -fsS -X POST "$BASE_URL/auth/login" \
         -H 'Content-Type: application/json' \
         -d "$(jq -nc --arg username "$1" --arg password "$2" '{username:$username,password:$password}')" \
         | jq -er '.access_token'
   }
   ADMIN_TOKEN=$(login "${ADMIN_USERNAME:-admin01}" "${ADMIN_PASSWORD:-SeatAdmin-2026!01}")
   USER01_TOKEN=$(login "${USER01_USERNAME:-user01}" "${USER01_PASSWORD:-SeatUser-2026!01}")
   USER02_TOKEN=$(login "${USER02_USERNAME:-user02}" "${USER02_PASSWORD:-SeatUser-2026!02}")
   USER03_TOKEN=$(login "${USER03_USERNAME:-user03}" "${USER03_PASSWORD:-SeatUser-2026!03}")
   USER04_TOKEN=$(login "${USER04_USERNAME:-user04}" "${USER04_PASSWORD:-SeatUser-2026!04}")
   USER05_TOKEN=$(login "${USER05_USERNAME:-user05}" "${USER05_PASSWORD:-SeatUser-2026!05}")
   USER06_TOKEN=$(login "${USER06_USERNAME:-user06}" "${USER06_PASSWORD:-SeatUser-2026!06}")
   USER07_TOKEN=$(login "${USER07_USERNAME:-user07}" "${USER07_PASSWORD:-SeatUser-2026!07}")
   USER08_TOKEN=$(login "${USER08_USERNAME:-user08}" "${USER08_PASSWORD:-SeatUser-2026!08}")
   export ADMIN_TOKEN USER01_TOKEN USER02_TOKEN USER03_TOKEN USER04_TOKEN USER05_TOKEN USER06_TOKEN USER07_TOKEN USER08_TOKEN

   show=$(curl -fsS -X POST "$BASE_URL/shows" \
      -H "Authorization: Bearer $ADMIN_TOKEN" \
      -H 'Content-Type: application/json' \
      -d '{"name":"burst-'"$(date +%s)-$$"'","seats":["A12","B01","B02","B03","B04","B05","B06","B07","B08","B09","B10","C01"],"price_paise":25000,"per_user_limit":2}')
   SHOW_ID=$(printf '%s' "$show" | jq -er '.id')
   export SHOW_ID

   printf 'Hot-seat test: %s requests, concurrency %s\n' "$REQUESTS" "$CONCURRENCY"
   seq 1 "$REQUESTS" | xargs -P "$CONCURRENCY" -I '{}' \
      "$0" __request hot '{}' "$USER01_TOKEN" 'hot-{}' A12

   count_status() {
      local phase=$1
      local expected=$2
      awk -v expected="$expected" '$1 == expected { count++ } END { print count + 0 }' "$WORK_DIR"/"$phase"-*.status
   }

   hot_success=$(count_status hot 201)
   hot_conflict=$(count_status hot 409)
   hot_server_errors=$(awk '$1 ~ /^5/ || $1 == "000" { count++ } END { print count + 0 }' "$WORK_DIR"/hot-*.status)
   printf '  hot-seat: %s confirmed, %s conflicts, %s server/network errors\n' \
      "$hot_success" "$hot_conflict" "$hot_server_errors"
   if [[ "$hot_success" -ne 1 || "$hot_conflict" -ne $((REQUESTS - 1)) || "$hot_server_errors" -ne 0 ]]; then
      printf 'FAIL: hot-seat ownership invariant failed\n' >&2
      exit 1
   fi

   winner=0
   for status_file in "$WORK_DIR"/hot-*.status; do
      if [[ $(<"$status_file") == 201 ]]; then
         winner=${status_file##*/hot-}
         winner=${winner%.status}
         break
      fi
   done

   printf 'Per-user limit test: 10 concurrent requests, limit 2\n'
   for request_number in $(seq 1 10); do
      seat=$(printf 'B%02d' "$request_number")
      printf '%s\n' "$request_number"
   done | xargs -P "$CONCURRENCY" -I '{}' sh -c \
      'seat=$(printf "B%02d" "$1"); exec "$2" __request limit "$1" "$3" "limit-$1" "$seat"' \
      sh '{}' "$0" "$USER02_TOKEN"

   limit_success=$(count_status limit 201)
   limit_conflict=$(count_status limit 409)
   limit_server_errors=$(awk '$1 ~ /^5/ || $1 == "000" { count++ } END { print count + 0 }' "$WORK_DIR"/limit-*.status)
   printf '  per-user: %s confirmed, %s conflicts, %s server/network errors\n' \
      "$limit_success" "$limit_conflict" "$limit_server_errors"
   if [[ "$limit_success" -ne 2 || "$limit_conflict" -ne 8 || "$limit_server_errors" -ne 0 ]]; then
      printf 'FAIL: per-user limit invariant failed\n' >&2
      exit 1
   fi

   printf 'Idempotency race: 10 identical concurrent requests\n'
   seq 1 10 | xargs -P "$CONCURRENCY" -I '{}' \
      "$0" __request idem '{}' "$USER03_TOKEN" same-key-race C01
   idem_created=$(count_status idem 201)
   idem_replayed=$(count_status idem 200)
   idem_server_errors=$(awk '$1 ~ /^5/ || $1 == "000" { count++ } END { print count + 0 }' "$WORK_DIR"/idem-*.status)
   printf '  idempotency: %s created, %s replayed, %s server/network errors\n' \
      "$idem_created" "$idem_replayed" "$idem_server_errors"
   if [[ "$idem_created" -ne 1 || "$idem_replayed" -ne 9 || "$idem_server_errors" -ne 0 ]]; then
      printf 'FAIL: concurrent idempotency invariant failed\n' >&2
      exit 1
   fi
   idem_reservation=$(jq -er '.reservation_id' "$WORK_DIR/idem-1.json")
   for response_file in "$WORK_DIR"/idem-*.json; do
      if [[ $(jq -er '.reservation_id' "$response_file") != "$idem_reservation" ]]; then
         printf 'FAIL: concurrent idempotent requests returned different reservations\n' >&2
         exit 1
      fi
   done

   original=$(jq -er '.reservation_id' "$WORK_DIR/hot-$winner.json")
   replay_status=$(curl -sS -o "$WORK_DIR/replay.json" -w '%{http_code}' \
      -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
      -H "Authorization: Bearer $USER01_TOKEN" \
      -H "Idempotency-Key: hot-$winner" \
      -H 'Content-Type: application/json' -d '{"seats":["A12"]}')
   replayed=$(jq -er '.reservation_id' "$WORK_DIR/replay.json")
   if [[ "$replay_status" != 200 || "$replayed" != "$original" ]]; then
      printf 'FAIL: identical idempotent retry did not return the original reservation\n' >&2
      exit 1
   fi

   mismatch_status=$(curl -sS -o "$WORK_DIR/mismatch.json" -w '%{http_code}' \
      -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
      -H "Authorization: Bearer $USER01_TOKEN" \
      -H "Idempotency-Key: hot-$winner" \
      -H 'Content-Type: application/json' -d '{"seats":["B10"]}')
   if [[ "$mismatch_status" != 409 ]] || [[ $(jq -r '.error.code' "$WORK_DIR/mismatch.json") != IDEMPOTENCY_KEY_REUSED ]]; then
      printf 'FAIL: same-key/different-body request was not rejected\n' >&2
      exit 1
   fi

   missing_auth_status=$(curl -sS -o "$WORK_DIR/missing-auth.json" -w '%{http_code}' \
      -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
      -H 'Idempotency-Key: missing-auth' -H 'Content-Type: application/json' \
      -d '{"seats":["B03"]}')
   if [[ "$missing_auth_status" != 401 ]] || [[ $(jq -r '.error.code' "$WORK_DIR/missing-auth.json") != UNAUTHORIZED ]]; then
      printf 'FAIL: a reservation without a bearer token was not rejected\n' >&2
      exit 1
   fi

   invalid_auth_status=$(curl -sS -o "$WORK_DIR/invalid-auth.json" -w '%{http_code}' \
      -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
      -H 'Authorization: Bearer invalid token' -H 'Idempotency-Key: invalid-auth' \
      -H 'Content-Type: application/json' -d '{"seats":["B03"]}')
   if [[ "$invalid_auth_status" != 401 ]] || [[ $(jq -r '.error.code' "$WORK_DIR/invalid-auth.json") != UNAUTHORIZED ]]; then
      printf 'FAIL: a malformed bearer token was not rejected\n' >&2
      exit 1
   fi

   missing_key_status=$(curl -sS -o "$WORK_DIR/missing-key.json" -w '%{http_code}' \
      -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
      -H "Authorization: Bearer $USER04_TOKEN" -H 'Content-Type: application/json' \
      -d '{"seats":["B03"]}')
   if [[ "$missing_key_status" != 400 ]] || [[ $(jq -r '.error.code' "$WORK_DIR/missing-key.json") != IDEMPOTENCY_KEY_REQUIRED ]]; then
      printf 'FAIL: a reservation without an idempotency key was not rejected\n' >&2
      exit 1
   fi

   partial_status=$(curl -sS -o "$WORK_DIR/partial.json" -w '%{http_code}' \
      -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
      -H "Authorization: Bearer $USER04_TOKEN" \
      -H 'Idempotency-Key: partial-request' \
      -H 'Content-Type: application/json' -d '{"seats":["A12","B03"]}')
   if [[ "$partial_status" != 409 ]] || [[ $(jq -r '.error.code' "$WORK_DIR/partial.json") != SEAT_TAKEN ]]; then
      printf 'FAIL: a partially unavailable multi-seat request was not rejected atomically\n' >&2
      exit 1
   fi
   if [[ $(curl -fsS "$BASE_URL/shows/$SHOW_ID" -H "Authorization: Bearer $USER01_TOKEN" | jq -r '.seats[] | select(.seat == "B03") | .status') != available ]]; then
      printf 'FAIL: all-or-nothing request reserved a free seat despite another seat being taken\n' >&2
      exit 1
   fi

   duplicate_status=$(curl -sS -o "$WORK_DIR/duplicate.json" -w '%{http_code}' \
      -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
      -H "Authorization: Bearer $USER04_TOKEN" \
      -H 'Idempotency-Key: duplicate-seat-request' \
      -H 'Content-Type: application/json' -d '{"seats":["B03","B03"]}')
   if [[ "$duplicate_status" != 400 ]] || [[ $(jq -r '.error.code' "$WORK_DIR/duplicate.json") != INVALID_REQUEST ]]; then
      printf 'FAIL: duplicate seats in one request were not rejected as invalid\n' >&2
      exit 1
   fi

   identity_status=$(curl -sS -o "$WORK_DIR/identity.json" -w '%{http_code}' \
      -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
      -H "Authorization: Bearer $USER05_TOKEN" \
      -H 'Idempotency-Key: identity-spoof-check' \
      -H 'Content-Type: application/json' \
      -d '{"seats":["B03"],"user_id":"spoofed-body-user"}')
   identity_reservation=$(jq -er '.reservation_id' "$WORK_DIR/identity.json")
   if [[ "$identity_status" != 201 ]] || [[ $(jq -r '.user_id' "$WORK_DIR/identity.json") != user05 ]]; then
      printf 'FAIL: reservation identity did not come from the authenticated JWT subject\n' >&2
      exit 1
   fi

   foreign_cancel_status=$(curl -sS -o "$WORK_DIR/foreign-cancel.json" -w '%{http_code}' \
      -X POST "$BASE_URL/reservations/$identity_reservation/cancel" \
      -H "Authorization: Bearer $USER06_TOKEN")
   if [[ "$foreign_cancel_status" != 403 ]] || [[ $(jq -r '.error.code' "$WORK_DIR/foreign-cancel.json") != RESERVATION_NOT_OWNED ]]; then
      printf 'FAIL: a different user was allowed to cancel the reservation\n' >&2
      exit 1
   fi

   owner_cancel_status=$(curl -sS -o "$WORK_DIR/owner-cancel.json" -w '%{http_code}' \
      -X POST "$BASE_URL/reservations/$identity_reservation/cancel" \
      -H "Authorization: Bearer $USER05_TOKEN")
   if [[ "$owner_cancel_status" != 200 ]] || [[ $(jq -r '.status' "$WORK_DIR/owner-cancel.json") != cancelled ]]; then
      printf 'FAIL: the reservation owner could not cancel the reservation\n' >&2
      exit 1
   fi

   repeated_cancel_status=$(curl -sS -o "$WORK_DIR/repeated-cancel.json" -w '%{http_code}' \
      -X POST "$BASE_URL/reservations/$identity_reservation/cancel" \
      -H "Authorization: Bearer $USER05_TOKEN")
   if [[ "$repeated_cancel_status" != 200 ]] || [[ $(jq -r '.status' "$WORK_DIR/repeated-cancel.json") != cancelled ]]; then
      printf 'FAIL: repeating cancellation was not an idempotent success\n' >&2
      exit 1
   fi

   cancelled_retry_status=$(curl -sS -o "$WORK_DIR/cancelled-retry.json" -w '%{http_code}' \
      -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
      -H "Authorization: Bearer $USER05_TOKEN" \
      -H 'Idempotency-Key: identity-spoof-check' \
      -H 'Content-Type: application/json' \
      -d '{"seats":["B03"],"user_id":"spoofed-body-user"}')
   if [[ "$cancelled_retry_status" != 200 ]] || [[ $(jq -r '.status' "$WORK_DIR/cancelled-retry.json") != cancelled ]]; then
      printf 'FAIL: replaying a cancelled idempotency key resurrected or changed its reservation\n' >&2
      exit 1
   fi

   printf 'Cancellation/rebook race: cancel an owned seat while another user retries it\n'
   race_owner_reservation=$(curl -fsS -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
      -H "Authorization: Bearer $USER06_TOKEN" \
      -H 'Idempotency-Key: cancel-rebook-owner' \
      -H 'Content-Type: application/json' -d '{"seats":["B04"]}' | jq -er '.reservation_id')
   (
      status=$(curl -sS -o "$WORK_DIR/race-cancel.json" -w '%{http_code}' \
         -X POST "$BASE_URL/reservations/$race_owner_reservation/cancel" \
         -H "Authorization: Bearer $USER06_TOKEN") || status=000
      printf '%s\n' "$status" > "$WORK_DIR/race-cancel.status"
   ) &
   cancel_pid=$!
   (
      status=$(curl -sS -o "$WORK_DIR/race-reserve.json" -w '%{http_code}' \
         -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
         -H "Authorization: Bearer $USER07_TOKEN" \
         -H 'Idempotency-Key: cancel-rebook-buyer' \
         -H 'Content-Type: application/json' -d '{"seats":["B04"]}') || status=000
      printf '%s\n' "$status" > "$WORK_DIR/race-reserve.status"
   ) &
   reserve_pid=$!
   wait "$cancel_pid"
   wait "$reserve_pid"
   race_cancel_status=$(<"$WORK_DIR/race-cancel.status")
   race_reserve_status=$(<"$WORK_DIR/race-reserve.status")
   if [[ "$race_cancel_status" != 200 ]]; then
      printf 'FAIL: owner cancellation in the cancel/rebook race returned %s\n' "$race_cancel_status" >&2
      exit 1
   fi
   race_seat_state=$(curl -fsS "$BASE_URL/shows/$SHOW_ID" -H "Authorization: Bearer $USER01_TOKEN" | jq -r '.seats[] | select(.seat == "B04") | .status')
   if [[ "$race_reserve_status" == 201 ]]; then
      race_expected_confirmed=6
      race_expected_available=6
      if [[ "$race_seat_state" != confirmed ]]; then
         printf 'FAIL: successful racing reservation is not the final seat owner\n' >&2
         exit 1
      fi
   elif [[ "$race_reserve_status" == 409 ]]; then
      race_expected_confirmed=5
      race_expected_available=7
      if [[ $(jq -r '.error.code' "$WORK_DIR/race-reserve.json") != SEAT_TAKEN || "$race_seat_state" != available ]]; then
         printf 'FAIL: losing racing reservation did not leave the released seat available\n' >&2
         exit 1
      fi
   else
      printf 'FAIL: racing reservation returned unexpected status %s\n' "$race_reserve_status" >&2
      exit 1
   fi

   rebook_status=$(curl -sS -o "$WORK_DIR/rebook.json" -w '%{http_code}' \
      -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
      -H "Authorization: Bearer $USER08_TOKEN" \
      -H 'Idempotency-Key: released-seat-rebook' \
      -H 'Content-Type: application/json' -d '{"seats":["B03"]}')
   if [[ "$rebook_status" != 201 ]] || [[ $(jq -r '.status' "$WORK_DIR/rebook.json") != confirmed ]]; then
      printf 'FAIL: a successfully cancelled seat could not be rebooked\n' >&2
      exit 1
   fi

   state=$(curl -fsS "$BASE_URL/shows/$SHOW_ID" -H "Authorization: Bearer $USER01_TOKEN")
   printf '%s\n' "$state" | jq '.counts'
   if ! printf '%s' "$state" | jq -e --argjson expected_confirmed "$race_expected_confirmed" --argjson expected_available "$race_expected_available" '
      .counts.total == 12 and
      .counts.confirmed == $expected_confirmed and
      .counts.available == $expected_available and
      .counts.held == 0 and
      (.counts.available + .counts.held + .counts.confirmed) == .counts.total
   ' >/dev/null; then
      printf 'FAIL: final seat state does not reconcile\n' >&2
      exit 1
   fi

   printf 'PASS: hot-seat, per-user limit, idempotency, atomic multi-seat, auth validation, identity, cancellation/replay, cancel-rebook race, and reconciliation invariants hold\n'