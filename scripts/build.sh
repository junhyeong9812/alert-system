#!/usr/bin/env bash
# 호스트에서 jar·Go 바이너리를 빌드하고 compose 이미지를 만든다 (spec I9: 빌드 도구 이미지를 내려받지 않음).
set -euo pipefail
cd "$(dirname "$0")/.."

for s in order product shipping notification; do
  echo "==> bootJar $s-service"
  (cd "services/$s-service" && ./gradlew -q bootJar)
done

echo "==> go build mock-slack"
(cd tools/mock-slack && CGO_ENABLED=0 go build -o bin/mock-slack .)

echo "==> go build analyzer"
(cd tools/analyzer && go build -o bin/analyzer .)

docker compose build
