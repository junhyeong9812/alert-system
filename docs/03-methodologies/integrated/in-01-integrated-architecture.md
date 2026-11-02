# IN-01. 통합 구조 — 단일 + 흐름 + 시스템 (슬롯형 후보)

> **상태**: 가설 — 실험 후 확정 · **범주**: 통합 · **대상 실패**: F1 ~ F7

## 한 줄 요약
어떤 단일 방법론도 세 신호(존재·부재·상태)를 모두 다루지 못한다. 그래서 통합 구조는 **범주별 슬롯**으로 정의하고, 각 슬롯을 무엇으로 채울지는 **실험 결과로 결정**한다.

## 슬롯 정의

```
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │ 슬롯 S  단일 전달      : { SG-02 | SG-03 | SG-05 }  + SG-04 (묶음 계층, 필수)  │
 │ 슬롯 F  흐름 감지      : { FL-01 | FL-02 | FL-03 }  ← 서비스 간 통신 모드에 종속│
 │ 슬롯 T  흐름 진단      : FL-04 (고정)                                          │
 │ 슬롯 R  안전망         : FL-05 (선택 — 실험 결과로 필요성 판단)                 │
 │ 슬롯 O  운영 감시      : SY-01 + SY-02 (고정, 변형 A/B는 실험으로)              │
 │ 슬롯 M  메타 감시      : SY-03 (고정, 변형 1/2는 실험으로)                      │
 └──────────────────────────────────────────────────────────────────────────────┘
```

### 슬롯별 실험 판단 기준

| 슬롯 | 후보 | 결정할 질문 | 판단 지표 |
|------|------|-----------|----------|
| S | SG-02 / SG-03 / SG-05 | 브로커 도입 비용이 얻는 것(재생·버퍼·분리)보다 큰가 | 유실·중복, 알림 지연, 리소스, 생산자 코드량 |
| F | FL-01 / FL-02 / FL-03 | 흐름 감지 즉시성 vs 시간 결합 | 주문 p95(하위 지연 시), 부재 감지 시간, 오탐률 |
| R | FL-05 유/무 | 실시간 메커니즘이 놓치는 것이 실제로 있나 | Sweeper만 찾아낸 불일치 건수 |
| O | 변형 A / B | F6 알림을 알림 서비스로 합류시킬 것인가 | 동시 장애 시 F6 도달 여부 |
| M | 변형 1 / 2 | heartbeat 체인에 알림 서비스를 포함할 것인가 | 감지 범위 vs 오탐 |

## 후보 구조 (현 시점 가설: S=SG-03+SG-04, F=FL-02, R=FL-05, O=A, M=2)

```
╔═══════════════════════════════ 비즈니스 서비스 ═══════════════════════════════════════╗
║  order ─OrderCreated─▶ ┌───────────── Kafka ─────────────┐ ◀─ product ◀─ shipping     ║
║  (tx: biz + outbox)    │ order/stock/shipment-events      │   (각자 tx: biz + outbox)  ║
║        │               │ domain-failures                  │                            ║
║        │ relay         │ alerts-retry-30s / 5m / dlq      │                            ║
║        └──────────────▶└───┬───────────────┬──────────────┘                            ║
║    traceparent (헤더) 전 구간 전파 ──▶ OTel Collector ──▶ Jaeger          [슬롯 T]     ║
╚════════════════════════════│═══════════════│════════════════════════════════════════════╝
                             │               │ (관찰 전용 consumer group)
                             │               ▼
                             │      ┌────────────────────┐
                             │      │ Saga 워치독 [슬롯 F]│ 기대 이벤트 + 기한 → FlowStuck
                             │      └─────────┬──────────┘
                             ▼                ▼
╔══════════════════════════ notification-service ═════════════════════════════════════════╗
║  ① 수신: domain-failures, FlowStuck, Mismatch, Alertmanager webhook(warn)                ║
║  ② ACL 정규화 → Alert{fingerprint, severity, owner, category, traceIds}                  ║
║  ③ 묶음 계층 [슬롯 S: SG-04]  group by fingerprint · 억제 · critical 즉시 · resolved     ║
║  ④ 라우팅 정책: (severity, owner, category) → [Notifier]                                 ║
║  ⑤ Notifier ─┬─ SlackNotifier ──▶ mock-slack                                             ║
║              └─ EmailNotifier ──▶ Mailpit                                                ║
║  ⑥ 실패: retry 토픽 → fallback → DLQ  (생산자 개입 없음)                                  ║
║  ⑦ /metrics: dlq_size, send_fail_rate, fingerprint_firing, e2e_latency                   ║
╚══════════════════════════════════════════════════════════════════════════▲═══════════════╝
        ▲ Mismatch                                                         │ warn webhook
┌───────┴───────────────┐                                                   │
│ Reconciler [슬롯 R]    │ 1분 주기, 대조 API                                 │
└───────────────────────┘                                                   │
╔══════════════════════════ 운영 경로 [슬롯 O] ═════════════════════════════│═══════════════╗
║  Prometheus ── scrape: 4개 서비스 /metrics, kafka/postgres exporter        │               ║
║      │        Blackbox: /health/live·ready, synthetic 흐름                  │               ║
║      ▼                                                                     │               ║
║  Alertmanager ── group/inhibit ──┬── warn ─────────────────────────────────┘               ║
║                                  ├── critical (ServiceDown, DLQ, DB) ──▶ Slack/Email 직접  ║
║                                  └── Watchdog ──▶ notification-service ──▶ dead-man-switch║
╚═══════════════════════════════════════════════════════════════════════════════│═══════════╝
                                                              [슬롯 M] 무응답 3분 │
                                                                                ▼
                                                                     Mailpit 직접 (우회)
```

## 실패 유형별 경로 추적

| 유형 | 감지 | 경로 | 1차 대응 |
|------|------|------|---------|
| F1 재고 부족 | product → outbox | Kafka → 알림(SG-04 집계, 비율은 SY-01) | 도메인 운영 |
| F2 DB 타임아웃 | 서비스 예외 → outbox | Kafka → 알림 (집계) + SY-01 에러율 | 서비스 팀 |
| F3 배송 실패 | shipping → ShipmentFailed | Kafka → 알림 | 주문 흐름 팀 |
| F4 배송 미시작 | 워치독 기한 초과 | FlowStuck → 알림 | 주문 흐름 팀 |
| F5 보상 실패 | 워치독(보상 기대) / Reconciler | → 알림 critical | 흐름 팀 + 데이터 |
| F6 서비스 사망 | Prometheus up / Blackbox | Alertmanager → 채널 직접 | on-call |
| F7 알림 경로 사망 | dead-man-switch | Mailpit 직접 | 플랫폼 |

## 설계 원칙 (이 구조가 지키는 불변식)

1. **생산자는 알림을 모른다** — outbox에 사실을 남기는 것까지.
2. **Downstream → Upstream 명령 없음** — 알림 실패는 알림 컨텍스트 안에서 끝난다.
3. **id는 역할별로 분리** — 멱등(결정적 키) · 진행(오프셋) · 문제(fingerprint) · 인과(traceId) · 검증(테스트 하네스 id).
4. **신호 형태마다 감지 수단이 있다** — 존재(push) · 부재(워치독/Reconciler) · 상태(Prometheus/Blackbox) · 메타(dead-man-switch).
5. **알림 경로와 감시 경로의 실패 영역 분리** — critical과 메타 경보는 알림 서비스를 우회한다.
6. **사람이 보는 알림 수 ∝ 문제 수** — 묶음·억제·해소.

## 이 구조의 비용 (정직한 평가)

| 비용 | 내용 |
|------|------|
| 컴포넌트 수 | 서비스 4 + Kafka + Postgres + Prometheus + Alertmanager + Blackbox + Collector + Jaeger + 감시자 |
| 정책 분산 | 라우팅이 Alertmanager와 알림 서비스 두 곳 (변형 A 선택 시) |
| 학습·운영 부담 | 소규모 팀에는 과할 수 있음 — **규모별 축소안**을 최종 문서에 함께 제시할 예정 |

### 규모별 축소안 (가설)
| 규모 | 구성 |
|------|------|
| 최소 | SG-02 + SG-04 + FL-01 + SY-01 + SY-03 (브로커 없음) |
| 중간 | 최소 + SG-03 대체 + FL-02 + FL-04 |
| 전체 | 위 후보 구조 |

## 확정 절차
1. [실험 계획](../../04-experiments/experiment-plan.md)의 Phase 1~4로 슬롯별 후보를 비교한다.
2. Phase 5에서 선정 조합으로 통합 실험(K4 혼돈 타임라인)을 돌린다.
3. 결과를 근거로 `docs/05-final/final-architecture.md`를 작성한다. 이 문서의 가설과 달라진 점과 그 근거를 명시한다.
