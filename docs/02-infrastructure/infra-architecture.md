# 인프라 구조

> 4개 서비스(order · product · shipping · notification)와 실험용 인프라를 로컬에서 어떻게 올릴지 정리한다.
> 모든 컴포넌트는 docker compose로 올리고, 방법론별로 필요한 것만 **프로필**로 켠다.
> 언어·프레임워크 등 구현 선택은 아직 정하지 않았다(§7 미확정 사항).

관련: [방법론 지도](../03-methodologies/README.md) · [실험 계획](../04-experiments/experiment-plan.md)

---

## 1. 구성 요소

### 서비스 (4)

| 서비스 | 역할 | 저장소 | 비고 |
|--------|------|--------|------|
| **order-service** | 주문 생성, 흐름 시작점. 동기 오케스트레이션 모드에서는 오케스트레이터 | `order_db` | outbox 테이블 포함 |
| **product-service** | 재고 차감 / 복원(보상) | `product_db` | outbox 테이블 포함 |
| **shipping-service** | 배송 생성 / 취소 | `shipping_db` | outbox 테이블 포함 |
| **notification-service** | 실패 사실 수신 → dedup·묶음 → 라우팅 → Notifier(Slack/Email) | `notification_db` | inbox, 알림 상태, DLQ, 워치독 기대 목록 |

공통으로 모든 서비스가 제공하는 것:
- `X-Fault` 헤더 기반 **장애 주입 훅** (§5)
- `/health/live`, `/health/ready`, `/metrics` (Prometheus 형식)
- 구조화 로그(JSON, stdout) + traceId 전파 (OpenTelemetry)

### 인프라

| 컴포넌트 | 용도 | 프로필 |
|----------|------|--------|
| **PostgreSQL** | 서비스별 DB (로컬에서는 인스턴스 1개 + DB 4개) | `core` |
| **mock-slack** | Slack Incoming Webhook 흉내. 수신 기록 + 장애 모드(5xx/429/지연) | `core` |
| **Mailpit** | SMTP 수신 sink + 웹 UI + API (Email 채널 검증) | `core` |
| **Kafka** (KRaft 단일 노드) | 이벤트 브로커 (SG-03/04/05, FL-02/03) | `broker` |
| **Kafka UI** | 토픽·컨슈머 랙 확인 | `broker` |
| **Debezium (Kafka Connect)** | CDC 기반 outbox relay (SG-05) | `cdc` |
| **Prometheus** | 메트릭 수집 + 규칙 평가 (SY-01) | `obs` |
| **Alertmanager** | 그룹핑·억제·라우팅 → webhook (SY-01) | `obs` |
| **Blackbox Exporter** | 외부 프로빙 (SY-02) | `obs` |
| **Grafana** | 대시보드 (실험 관찰) | `obs` |
| **Loki + Alloy** | 로그 수집·규칙 (SG-07) | `logs` |
| **OTel Collector + Jaeger** | 트레이스 수집·조회 (FL-04) | `trace` |
| **dead-man-switch** | 외부 heartbeat 감시자 흉내 (SY-03). 일정 주기 ping이 끊기면 Mailpit으로 직접 발송 | `obs` |
| **Toxiproxy** | 네트워크 장애 주입 (지연·차단·대역폭) — 서비스↔DB, 서비스↔Kafka 사이 | `chaos` |
| **k6** | 부하 + 장애 주입 시나리오 실행 | `loadtest` |
| **analyzer** | 실험 정답(ground truth)과 sink 수신 기록 대조 | `loadtest` |

---

## 2. 전체 구조

```
╔══════════════════════════════════════════════════════════════════════════════════════════╗
║  [loadtest]   k6  ──(HTTP + X-Fault, X-Test-Run-Id, X-Request-Id)──┐      analyzer        ║
║                                                                     │   (ground truth 대조) ║
╚═════════════════════════════════════════════════════════════════════│═══════════▲═════════╝
                                                                      ▼           │
╔═══════════════════════════ [core] 비즈니스 서비스 ═══════════════════════════════│═════════╗
║                                                                                 │         ║
║   ┌───────────────┐  동기(REST)   ┌───────────────┐  동기(REST)  ┌───────────────┐        ║
║   │ order-service │──────────────▶│product-service│─────────────▶│shipping-service│       ║
║   │  ├ outbox     │               │  ├ outbox     │              │  ├ outbox     │        ║
║   │  ├ /metrics   │               │  ├ /metrics   │              │  ├ /metrics   │        ║
║   │  └ /health    │               │  └ /health    │              │  └ /health    │        ║
║   └──┬─────┬──────┘               └──┬─────┬──────┘              └──┬─────┬──────┘        ║
║      │     │  (코레오그래피 모드: 이벤트로 연결 ──────── Kafka ─────────)  │     │         ║
║      │     │                         │     │                         │     │              ║
║      │  ┌──▼─────────────────────────▼─────▼─────────────────────────▼──┐  │              ║
║      │  │ [chaos] Toxiproxy  (서비스 ↔ DB · Kafka 사이 지연/차단 주입)    │  │              ║
║      │  └──┬──────────────────────────────┬─────────────────────────────┘  │              ║
║      │     ▼                              ▼                                │              ║
║      │  ┌──────────────────────┐   ┌────────────────────────┐              │              ║
║      │  │ PostgreSQL           │   │ [broker] Kafka (KRaft) │◀── [cdc] Debezium (WAL)     ║
║      │  │ order/product/       │   │  domain-failures       │              │              ║
║      │  │ shipping/notification│   │  order-events …        │              │              ║
║      │  └──────────────────────┘   │  alerts-retry / dlq    │              │              ║
║      │                             └───────────┬────────────┘              │              ║
║      │   실패 사실 (방법론에 따라 HTTP | Kafka | 폴링 | 로그)                 │              ║
║      └────────────────────────────┬────────────┴───────────────────────────┘              ║
║                                   ▼                                                       ║
║                     ┌──────────────────────────────┐                                      ║
║                     │ notification-service          │◀──── webhook ────┐                   ║
║                     │  ├ inbox / dedup / fingerprint│                  │                   ║
║                     │  ├ 묶음 윈도우 · 라우팅 정책    │                  │                   ║
║                     │  ├ Saga 워치독 (기대 목록)     │                  │                   ║
║                     │  ├ Notifier ─┬─ Slack          │                  │                   ║
║                     │  │           └─ Email          │                  │                   ║
║                     │  └ retry → DLQ, /metrics       │                  │                   ║
║                     └──────┬──────────────┬─────────┘                  │                   ║
║                            ▼              ▼                            │                   ║
║                     ┌────────────┐  ┌───────────┐                       │                   ║
║                     │ mock-slack │  │  Mailpit  │──────────────────▶ analyzer (수신 기록)  ║
║                     └────────────┘  └───────────┘                       │                   ║
╚═════════════════════════════════════════▲══════════════════════════════│═══════════════════╝
                                          │ 직접 Email (알림 서비스 우회)   │
╔═══════════════════════════ [obs] 운영 경로 ═══════════════════════════════│═══════════════════╗
║                                                                         │                   ║
║   ┌────────────┐ scrape /metrics (4개 서비스 + Kafka + Postgres exporter)  │                   ║
║   │ Prometheus │──────────────────────────────────────────────────────    │                   ║
║   │ 규칙 평가   │──▶ ┌──────────────┐  group/inhibit/route                 │                   ║
║   └─────┬──────┘    │ Alertmanager │────────────────────────────────────────┘                  ║
║         │           └──────┬───────┘                                                         ║
║   ┌─────▼──────────┐       │ Watchdog 알림 (항상 발화)                                         ║
║   │Blackbox Exporter│      ▼                                                                  ║
║   │ /health 프로빙  │  ┌──────────────────┐  ping 끊김 → Mailpit 직접 발송                     ║
║   └────────────────┘  │ dead-man-switch  │──────────────────────────────────────▶            ║
║                       └──────────────────┘                                                    ║
║   Grafana (대시보드)   [logs] Loki + Alloy    [trace] OTel Collector + Jaeger                   ║
╚═══════════════════════════════════════════════════════════════════════════════════════════════╝
```

### 경로별 구분

| 경로 | 흐름 | 잡는 실패 |
|------|------|----------|
| **도메인 경로** (push) | 서비스 → (outbox/브로커/HTTP) → notification-service → 채널 | F1, F2, F3 |
| **흐름 감시 경로** | 이벤트 → 워치독 / Process Manager → notification-service | F4, F5 |
| **운영 경로** (pull) | Prometheus · Blackbox → Alertmanager → notification-service 또는 채널 직접 | F6, F1·F2 비율 이상 |
| **메타 경로** | Alertmanager Watchdog → dead-man-switch → Mailpit 직접 | F7 |

---

## 3. 서비스 간 통신 모드

흐름 방법론에 따라 서비스 간 연결 방식을 바꾼다. 서비스 코드는 하나로 두고 **설정(환경변수)으로 모드를 전환**한다.

```
 [모드 A: 동기 오케스트레이션 — FL-01]
   order ──REST──▶ product ──(성공)──▶ order ──REST──▶ shipping
     └ 실패 시 order가 보상 호출 (product 재고 복원)

 [모드 B: 이벤트 코레오그래피 — FL-02]
   order ─OrderCreated─▶ Kafka ─▶ product ─StockReserved─▶ Kafka ─▶ shipping ─ShipmentCreated─▶ …
     └ 실패 시 각 서비스가 실패 이벤트 발행 → 이전 단계가 보상

 [모드 C: 이벤트 오케스트레이션 — FL-03]
   order(Process Manager) ─ReserveStock cmd─▶ Kafka ─▶ product ─StockReserved─▶ Kafka ─▶ order(PM) ─CreateShipment cmd─▶ …
```

---

## 4. 실행 방식 — compose 프로필

```
docker compose --profile core --profile obs up -d                      # 기본
docker compose --profile core --profile broker --profile obs up -d     # 브로커 기반 방법론
docker compose --profile loadtest run k6 run /scripts/<scenario>.js    # 실험 실행
```

| 방법론 | core | broker | cdc | obs | logs | trace | chaos |
|--------|:----:|:------:|:---:|:---:|:----:|:-----:|:-----:|
| SG-01 ID 핸드셰이크 | ● | | | ◐ | | | ● |
| SG-02 Outbox + 멱등 Inbox | ● | | | ● | | | ● |
| SG-03 브로커 at-least-once | ● | ● | | ● | | | ● |
| SG-04 Fingerprint 묶음 처리 | ● | ● | | ● | | | ● |
| SG-05 CDC Outbox | ● | ● | ● | ● | | | ● |
| SG-06 Pull 폴링 | ● | | | ● | | | ● |
| SG-07 로그 기반 | ● | | | ● | ● | | ● |
| FL-01 동기 오케스트레이션 | ● | | | ● | | ● | ● |
| FL-02 코레오그래피 + 워치독 | ● | ● | | ● | | ● | ● |
| FL-03 Process Manager | ● | ● | | ● | | ● | ● |
| FL-04 trace 상관 | ● | ◐ | | ● | | ● | |
| FL-05 Reconciliation | ● | ◐ | | ● | | | ● |
| SY-01~03 | ● | | | ● | | | ● |
| IN-01 통합 | ● | ● | | ● | ● | ● | ● |

(● 필수 · ◐ 변형에 따라)

### 포트 계획 (초안)

| 컴포넌트 | 포트 |
|----------|------|
| order / product / shipping / notification | 8081 / 8082 / 8083 / 8084 |
| PostgreSQL | 5432 |
| Kafka / Kafka UI | 9092 / 8090 |
| mock-slack | 8070 |
| Mailpit (UI / SMTP) | 8025 / 1025 |
| Prometheus / Alertmanager / Grafana | 9090 / 9093 / 3000 |
| Blackbox Exporter | 9115 |
| Jaeger UI | 16686 |
| Loki | 3100 |
| Toxiproxy API | 8474 |
| dead-man-switch | 8060 |

---

## 5. 장애 주입 지점

```
            ┌──────── ① 애플리케이션 훅 (X-Fault 헤더) ────────┐
            │  biz | tech | latency=<ms> | crash-after-commit │
            ▼                                                 │
 k6 ──▶ [service] ──② Toxiproxy──▶ [Postgres / Kafka]          │
            │                                                 │
            │ ③ 컨테이너 조작: docker kill / pause / stop      │
            ▼                                                 │
   [notification-service] ──▶ ④ mock-slack 장애 모드 ──────────┘
                               (5xx | 429 | latency | blackhole)
            ⑤ 모니터링 스택 조작: Prometheus / Alertmanager stop
```

| 지점 | 방법 | 대상 실패 |
|------|------|----------|
| ① 앱 훅 | `X-Fault: biz` → 도메인 거절, `tech` → 예외, `latency=500` → 지연, `crash-after-commit` → 커밋 직후 프로세스 종료 | F1, F2, F3, P4(dual write) |
| ② Toxiproxy | DB/Kafka 연결 지연·차단 | F2, F6 |
| ③ 컨테이너 | 서비스 kill/pause, 소비자 정지 | F4, F6, F7 |
| ④ mock-slack | 모드 전환 API (`POST /_control/mode`) | F7 (채널 장애) |
| ⑤ 모니터링 스택 | Prometheus/Alertmanager 중지 | F7 (메타) |

- 앱 훅은 **실험 전용 코드**다. `FAULT_INJECTION_ENABLED=true`일 때만 동작하도록 해서 아키텍처 로직과 분리한다.
- `X-Test-Run-Id`, `X-Request-Id`는 실패 이벤트에 **관측용 속성**으로만 실린다. 아키텍처의 dedup·라우팅 로직은 이 값을 쓰지 않는다(정답 비교 전용).

---

## 6. 리소스와 측정 신뢰성

- 단일 호스트에서 서비스·인프라·k6가 CPU를 나눠 쓴다. **절대 수치보다 방법론 간 상대 비교**를 목적으로 한다.
- 컨테이너별 CPU/메모리 제한(`deploy.resources.limits`)을 고정해 실행마다 조건을 같게 한다.
- k6는 open model(`constant-arrival-rate`)로 돌려 coordinated omission(서버가 느려지면 부하도 같이 줄어드는 착시)을 피한다.
- 상세 측정 방법은 [실험 계획](../04-experiments/experiment-plan.md) 참조.

---

## 7. 미확정 사항 (구현 명세에서 결정)

| 항목 | 후보 | 판단 기준 |
|------|------|----------|
| 서비스 언어/프레임워크 | Spring Boot (Kotlin/Java) · Go · Node | 숙련도, 면접 맥락, 메모리 사용량 |
| 브로커 | Kafka · Redpanda · RabbitMQ | 재생 필요성, 로컬 리소스 |
| 트레이스 백엔드 | Jaeger · Tempo | 설정 단순성 |
| mock-slack 구현 | WireMock · 자체 구현 경량 서버 | 장애 모드 전환 API 필요 여부 |
| DB 분리 수준 | 인스턴스 1개 + DB 4개 · 인스턴스 4개 | 리소스 vs 장애 격리 실험 필요성 |
