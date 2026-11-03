#!/usr/bin/env bash
# Phase 1 (SG-01 기준선 + SG-01+ 운영 경로 변형) 전체 실행 — 실험 계획 §7 SG-01, 3회 반복.
# 반복은 시나리오를 섞어 돌려(rep1 전체 → rep2 전체 …) 시간에 따른 드리프트가 한 시나리오에 몰리지 않게 한다.
set -euo pipefail
cd "$(dirname "$0")/.."

run() { bash scripts/run-experiment.sh "$@" || echo "!! run failed: $*"; }

echo "== SG-01 (운영 경로 없음)"
docker compose stop prometheus alertmanager grafana > /dev/null 2>&1 || true
run k0-calibration 1
for rep in 1 2 3; do
  for s in k1-baseline k2-mixed k3-storm k6-fi07 k6-fi09 k6-fi14; do
    run "$s" "$rep"
  done
done
DURATION=3m COOLDOWN_SECONDS=120 run obs-servicedown 1   # 운영 경로가 없을 때의 서비스 다운 (감지 수단 없음 확인)

echo "== SG-01+ (운영 경로 = Prometheus + Alertmanager + Grafana)"
docker compose --profile obs up -d prometheus alertmanager grafana > /dev/null
sleep 20
for rep in 1 2 3; do
  DURATION=3m COOLDOWN_SECONDS=120 run obs-servicedown "plus$rep"
done
echo "== phase1 done"
