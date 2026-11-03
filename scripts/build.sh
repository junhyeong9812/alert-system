#!/usr/bin/env bash
# 이미지 빌드(멀티스테이지 — spec I9)와 호스트용 analyzer 빌드
set -euo pipefail
cd "$(dirname "$0")/.."

echo "==> go build analyzer (host tool)"
(cd tools/analyzer && go build -o bin/analyzer .)

docker compose --profile obs build
