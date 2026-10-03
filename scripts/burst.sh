   #!/usr/bin/env bash
   set -euo pipefail
   PATH="${PATH:-}:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
   export PATH

   if [[ "${1:-}" == "__request" ]]; then
      phase=$2
      number=$3
      user_id=$4
      key=$5
      seat=$6
      response_file="$WORK_DIR/$phase-$number.json"
      status=$(curl -sS -o "$response_file" -w '%{http_code}' \
         -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
         -H "Authorization: Bearer $user_id" \
         -H "Idempotency-Key: $key" \
         -H 'Content-Type: application/json' \
         -d "{\"seats\":[\"$seat\"]}") || status=000
      printf '%s\n' "$status" > "$WORK_DIR/$phase-$number.status"
      exit 0
   fi

   BASE_URL=${1:?Usage: scripts/burst.sh <BASE_URL>}
   BASE_URL=${BASE_URL%/}
   ADMIN_TOKEN=${ADMIN_TOKEN:-local-admin-token}
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

   show=$(curl -fsS -X POST "$BASE_URL/shows" \
      -H "Authorization: Bearer $ADMIN_TOKEN" \
      -H 'Content-Type: application/json' \
      -d '{"name":"burst-'"$(date +%s)-$$"'","seats":["A12","B01","B02","B03","B04","B05","B06","B07","B08","B09","B10","C01"],"price_paise":25000,"per_user_limit":2}')
   SHOW_ID=$(printf '%s' "$show" | jq -er '.id')
   export SHOW_ID

   printf 'Hot-seat test: %s requests, concurrency %s\n' "$REQUESTS" "$CONCURRENCY"
   seq 1 "$REQUESTS" | xargs -P "$CONCURRENCY" -I '{}' \
      "$0" __request hot '{}' 'burst-hot-{}' 'hot-{}' A12

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
      'seat=$(printf "B%02d" "$1"); exec "$2" __request limit "$1" burst-limited-user "limit-$1" "$seat"' \
      sh '{}' "$0"

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
      "$0" __request idem '{}' burst-same-key-user same-key-race C01
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
      -H "Authorization: Bearer burst-hot-$winner" \
      -H "Idempotency-Key: hot-$winner" \
      -H 'Content-Type: application/json' -d '{"seats":["A12"]}')
   replayed=$(jq -er '.reservation_id' "$WORK_DIR/replay.json")
   if [[ "$replay_status" != 200 || "$replayed" != "$original" ]]; then
      printf 'FAIL: identical idempotent retry did not return the original reservation\n' >&2
      exit 1
   fi

   mismatch_status=$(curl -sS -o "$WORK_DIR/mismatch.json" -w '%{http_code}' \
      -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
      -H "Authorization: Bearer burst-hot-$winner" \
      -H "Idempotency-Key: hot-$winner" \
      -H 'Content-Type: application/json' -d '{"seats":["B10"]}')
   if [[ "$mismatch_status" != 409 ]] || [[ $(jq -r '.error.code' "$WORK_DIR/mismatch.json") != IDEMPOTENCY_KEY_REUSED ]]; then
      printf 'FAIL: same-key/different-body request was not rejected\n' >&2
      exit 1
   fi

   state=$(curl -fsS "$BASE_URL/shows/$SHOW_ID")
   printf '%s\n' "$state" | jq '.counts'
   if ! printf '%s' "$state" | jq -e '
      .counts.total == 12 and
      .counts.confirmed == 4 and
      .counts.available == 8 and
      .counts.held == 0 and
      (.counts.available + .counts.held + .counts.confirmed) == .counts.total
   ' >/dev/null; then
      printf 'FAIL: final seat state does not reconcile\n' >&2
      exit 1
   fi

   printf 'PASS: hot-seat, per-user limit, idempotency, and reconciliation invariants hold\n'