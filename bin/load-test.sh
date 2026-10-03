#!/usr/bin/env bash
set -euo pipefail
PATH="${PATH:-}:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin"
export PATH

if [[ "${1:-}" == "__request" ]]; then
  request_number=$2
  response=$(curl -sS \
    --connect-timeout "$CONNECT_TIMEOUT" \
    --max-time "$REQUEST_TIMEOUT" \
    -o /dev/null \
    -w '%{http_code}\t%{time_total}' \
    -X POST "$BASE_URL/shows/$SHOW_ID/reserve" \
    -H "Authorization: Bearer $USER_TOKEN" \
    -H "Idempotency-Key: load-$request_number" \
    -H 'Content-Type: application/json' \
    -d '{"seats":["HOT"]}') || response=$'000\t0.000'
  printf '%s\n' "$response" >> "$RESULTS_FILE"
  exit 0
fi

usage() {
  printf 'Usage: REQUESTS=1000 CONCURRENCY=100 %s <BASE_URL>\n' "$0"
  printf 'Defaults: REQUESTS=1000 CONCURRENCY=100; maximum REQUESTS=20000\n'
}

BASE_URL=${1:-}
if [[ -z "$BASE_URL" ]]; then
  usage >&2
  exit 2
fi
BASE_URL=${BASE_URL%/}
REQUESTS=${REQUESTS:-1000}
CONCURRENCY=${CONCURRENCY:-100}
REQUEST_TIMEOUT=${REQUEST_TIMEOUT:-30}
CONNECT_TIMEOUT=${CONNECT_TIMEOUT:-5}

if [[ ! "$REQUESTS" =~ ^[0-9]+$ || ! "$CONCURRENCY" =~ ^[0-9]+$ ]]; then
  printf 'REQUESTS and CONCURRENCY must be positive integers.\n' >&2
  exit 2
fi
REQUESTS=$((10#$REQUESTS))
CONCURRENCY=$((10#$CONCURRENCY))
if (( REQUESTS < 2 || REQUESTS > 20000 || CONCURRENCY < 1 )); then
  printf 'Use 2-20000 requests and a concurrency value of at least 1.\n' >&2
  exit 2
fi
if (( CONCURRENCY > REQUESTS )); then
  CONCURRENCY=$REQUESTS
fi

for tool in curl jq xargs seq awk sort perl; do
  command -v "$tool" >/dev/null || { printf 'Missing required command: %s\n' "$tool" >&2; exit 1; }
done

WORK_DIR=$(mktemp -d)
RESULTS_FILE="$WORK_DIR/results.tsv"
touch "$RESULTS_FILE"
if [[ "${KEEP_RESULTS:-0}" == "1" ]]; then
  trap 'printf "Results retained at %s\\n" "$WORK_DIR"' EXIT
else
  trap 'rm -rf "$WORK_DIR"' EXIT
fi
export BASE_URL CONNECT_TIMEOUT REQUEST_TIMEOUT RESULTS_FILE

if ! curl --retry 5 --retry-all-errors --retry-delay 1 --retry-max-time 15 \
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
USER_TOKEN=$(login "${USER_USERNAME:-user01}" "${USER_PASSWORD:-SeatUser-2026!01}")
export USER_TOKEN

show=$(curl -fsS -X POST "$BASE_URL/shows" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"load-test-'"$(date +%s)-$$"'","seats":["HOT"],"price_paise":25000,"per_user_limit":1}')
SHOW_ID=$(printf '%s' "$show" | jq -er '.id')
export SHOW_ID

now_seconds() {
  perl -MTime::HiRes=time -e 'printf "%.6f", time'
}

printf 'Hot-seat load test\n'
printf '  URL:         %s\n' "$BASE_URL"
printf '  Show:        %s\n' "$SHOW_ID"
printf '  Requests:    %s\n' "$REQUESTS"
printf '  Concurrency: %s\n' "$CONCURRENCY"

start_time=$(now_seconds)
seq 1 "$REQUESTS" | xargs -P "$CONCURRENCY" -I '{}' "$0" __request '{}'
end_time=$(now_seconds)
elapsed=$(awk -v start="$start_time" -v end="$end_time" 'BEGIN { printf "%.3f", end - start }')

read -r created conflicts server_errors unexpected < <(awk -F '\t' '
  $1 == 201 { created++ }
  $1 == 409 { conflicts++ }
  $1 ~ /^5/ || $1 == "000" { server_errors++ }
  $1 != 201 && $1 != 409 && $1 !~ /^5/ && $1 != "000" { unexpected++ }
  END { print created + 0, conflicts + 0, server_errors + 0, unexpected + 0 }
' "$RESULTS_FILE")

requests_per_second=$(awk -v count="$REQUESTS" -v elapsed="$elapsed" \
  'BEGIN { if (elapsed > 0) printf "%.1f", count / elapsed; else print "0.0" }')
read -r p50 p95 max_latency < <(cut -f2 "$RESULTS_FILE" | sort -n | awk '
  { latency[NR] = $1 }
  END {
    if (NR == 0) { print "0.000 0.000 0.000"; exit }
    p50 = int(NR * 0.50 + 0.999)
    p95 = int(NR * 0.95 + 0.999)
    printf "%.3f %.3f %.3f\n", latency[p50], latency[p95], latency[NR]
  }
')

printf '\nResults\n'
printf '  Duration:       %s s\n' "$elapsed"
printf '  Throughput:     %s requests/s\n' "$requests_per_second"
printf '  201 created:    %s\n' "$created"
printf '  409 conflicts:  %s\n' "$conflicts"
printf '  5xx/network:    %s\n' "$server_errors"
printf '  Other status:   %s\n' "$unexpected"
printf '  Latency p50:    %s s\n' "$p50"
printf '  Latency p95:    %s s\n' "$p95"
printf '  Latency max:    %s s\n' "$max_latency"

state=$(curl -fsS "$BASE_URL/shows/$SHOW_ID" -H "Authorization: Bearer $USER_TOKEN")
printf '\nFinal seat counts\n'
printf '%s\n' "$state" | jq '.counts'
if ! printf '%s' "$state" | jq -e '
  .counts.total == 1 and
  .counts.confirmed == 1 and
  .counts.available == 0 and
  .counts.held == 0 and
  (.counts.available + .counts.held + .counts.confirmed) == .counts.total
' >/dev/null; then
  printf 'FAIL: final inventory does not match the single winning request.\n' >&2
  exit 1
fi

if (( created != 1 || conflicts != REQUESTS - 1 || server_errors != 0 || unexpected != 0 )); then
  printf 'FAIL: expected one 201, %s 409 responses, and no other statuses.\n' "$((REQUESTS - 1))" >&2
  exit 1
fi

printf '\nPASS: one hot-seat reservation, clean conflicts, and reconciled inventory.\n'