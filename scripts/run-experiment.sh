#!/usr/bin/env bash
# 실험 1회 실행: 상태 초기화 → 워밍업 → 시나리오(+장애 타임라인) → 쿨다운 → 수집 → analyzer
#   scripts/run-experiment.sh <scenario> [rep]
#   scenario: k0-calibration | k1-baseline | k2-mixed | k3-storm | k6-fi07 | k6-fi09 | k6-fi14 | obs-servicedown
# 환경변수: WARMUP_DURATION(기본 120s) COOLDOWN_SECONDS(기본 90) SKIP_WARMUP=1
set -euo pipefail
cd "$(dirname "$0")/.."

SCEN=${1:?scenario}
REP=${2:-1}
RUN_ID="${SCEN}-$(date +%Y%m%dT%H%M%S)-r${REP}"
OUT="experiments/runs/${RUN_ID}"
mkdir -p "$OUT"
EVENTS="$OUT/events.jsonl"
: > "$EVENTS"

log() { echo "[$(date +%H:%M:%S)] $*"; }
event() { printf '{"t":"%s","event":"%s","detail":"%s"}\n' "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)" "$1" "${2:-}" >> "$EVENTS"; log "event $1 ${2:-}"; }
psql() { docker compose exec -T postgres psql -U alert -d "$1" -qAtc "$2"; }
slack_mode() { curl -sf -X POST localhost:8070/_control/mode -d "{\"mode\":\"$1\"}" > /dev/null; }
callbacks() { for p in order product shipping; do curl -sf -X POST "localhost:8474/proxies/${p}-callback" -d "{\"enabled\":$1}" > /dev/null; done; }

wait_ready() {
  for _ in $(seq 1 90); do
    local ok=0
    for p in 8081 8082 8083 8084; do
      [ "$(curl -s -o /dev/null -w '%{http_code}' "localhost:$p/health/ready")" = 200 ] && ok=$((ok + 1))
    done
    [ "$ok" = 4 ] && return 0
    sleep 2
  done
  echo "services not ready" >&2
  return 1
}

reset_state() {
  psql order_db "truncate orders, alert_status restart identity"
  psql product_db "truncate reservations, alert_status; update products set stock = 1000000000"
  psql shipping_db "truncate shipments, alert_status"
  psql notification_db "truncate inbox"
  curl -sf -X POST localhost:8070/_control/reset > /dev/null
  curl -sf -X POST localhost:8474/reset > /dev/null
  curl -sf -X DELETE localhost:8025/api/v1/messages > /dev/null
}

# 시나리오별 장애 타임라인 (k6 시작 기준 초)
timeline() {
  case "$SCEN" in
    k6-fi09)
      sleep 60; event fault:channel-5xx mock-slack; slack_mode 5xx
      sleep 60; event recover:channel mock-slack; slack_mode ok ;;
    k6-fi14)
      sleep 60; event fault:callback-cut toxiproxy; callbacks false
      sleep 60; event recover:callback toxiproxy; callbacks true ;;
    obs-servicedown)
      sleep 30; event fault:stop shipping-service; docker compose stop shipping-service > /dev/null
      sleep 90; event recover:start shipping-service; docker compose start shipping-service > /dev/null ;;
  esac
}

log "run $RUN_ID"
wait_ready
if [ "${SKIP_WARMUP:-0}" != 1 ]; then
  log "warmup ${WARMUP_DURATION:-120s}"
  k6 run -q -e RUN_ID="warmup-$RUN_ID" -e WARMUP_DURATION="${WARMUP_DURATION:-120s}" loadtest/k6/warmup.js > /dev/null 2>&1
fi
reset_state
event run:start "$SCEN"

K6_SCRIPT="loadtest/k6/${SCEN}.js"
[ "$SCEN" = obs-servicedown ] && K6_SCRIPT=loadtest/k6/k2-mixed.js
timeline &
TL=$!
set +e
k6 run -q -e RUN_ID="$RUN_ID" -e DURATION="${DURATION:-10m}" \
  --log-output=file="$OUT/expected.log" --log-format=raw \
  --summary-export="$OUT/k6-summary.json" "$K6_SCRIPT" > "$OUT/k6-stdout.txt" 2>&1
K6_EXIT=$?
set -e
wait "$TL"
event run:k6-done "exit=$K6_EXIT"

log "cooldown ${COOLDOWN_SECONDS:-90}s"
sleep "${COOLDOWN_SECONDS:-90}"
wait_ready || true

curl -sf localhost:8070/_control/records > "$OUT/records.jsonl"
{
  for db in order product shipping; do
    echo "## ${db}_db alert_status"
    psql "${db}_db" "select status, count(*), sum(retry_count) from alert_status group by status order by status"
  done
  # dual write 공백(L6) 독립 측정: 비즈니스 실패 기록 수 − alert_status 수 (실패 기록은 알림보다 먼저 커밋된다)
  echo "## dual-write gap (failure records | alert_status rows)"
  echo "order $(psql order_db "select (select count(*) from orders where status = 'FAILED' and failure_reason in ('ORDER_LIMIT_EXCEEDED','TECH_ERROR')) || '|' || (select count(*) from alert_status)")"
  echo "product $(psql product_db "select (select count(*) from reservations where status in ('REJECTED','FAILED')) || '|' || (select count(*) from alert_status where payload->>'errorCode' <> 'COMPENSATION_FAILED')")"
  echo "shipping $(psql shipping_db "select (select count(*) from shipments where status = 'FAILED') || '|' || (select count(*) from alert_status)")"
  echo "## order_db orders"
  psql order_db "select status, count(*) from orders group by status order by status"
  echo "## notification_db inbox (status | count | fail | sent | received | retry_cmd)"
  psql notification_db "select status, count(*), sum(fail_count), sum(sent_count), sum(received_count), sum(retry_command_count) from inbox group by status order by status"
  echo "## notification_db inbox sent more than once"
  psql notification_db "select count(*) from inbox where sent_count > 1"
  echo "## container restarts"
  for s in order-service product-service shipping-service notification-service; do
    echo "$s $(docker inspect -f '{{.RestartCount}}' "alert-system-$s-1")"
  done
  echo "## emails (mailpit)"
  curl -s localhost:8025/api/v1/messages | python3 -c "import sys,json; print(json.load(sys.stdin)['total'])"
} > "$OUT/db-state.txt"

tools/analyzer/bin/analyzer -run-id "$RUN_ID" -expected "$OUT/expected.log" -records "$OUT/records.jsonl" \
  -events "$EVENTS" -out "$OUT/report.json" -md "$OUT/report.md" > /dev/null
log "done → $OUT (k6 exit=$K6_EXIT)"
cat "$OUT/report.md"
cat "$OUT/db-state.txt"
