# FL-03. 이벤트 기반 오케스트레이터 (Process Manager)

> **상태**: P2 · **범주**: 흐름 · **패턴**: 비동기 오케스트레이션 (Kafka + 중앙 상태) · **대상 실패**: F3, F4, F5 · **id 의존도**: 중간 — 흐름 인스턴스 = **sagaId(orderId)**

## 한 줄 요약
FL-01처럼 흐름을 한 주체(Process Manager)가 지휘하되, 호출은 FL-02처럼 **비동기 명령/이벤트**로 한다. 흐름 상태와 기한(timeout)이 PM 안에 있으므로 **존재 실패와 부재 실패를 한곳에서** 감지한다.

## 핵심 아이디어
- PM은 상태 머신이다: `STARTED → STOCK_RESERVING → SHIPPING_CREATING → DONE` / 실패 분기 `COMPENSATING → COMPENSATED | COMPENSATION_FAILED`.
- 각 단계에서 명령(`ReserveStock`)을 보내고 **응답 이벤트 기한**을 함께 등록한다. 기한 초과 시 재시도·보상·알림을 PM이 결정한다.
- FL-02의 워치독이 "관찰자"라면, PM은 "참여자"다. 기한 초과 시 **직접 행동**(재시도/보상)할 수 있다.
- 시간 결합은 없다(비동기). 흐름 소유자는 명확하다(PM).

## 전체 구조

```
 [흐름 경로]
 ┌────────────────────────────────────────────────────────────────────────┐
 │ order-service = Process Manager                                         │
 │  saga: {sagaId, state, step, deadline, attempts, traceId}               │
 │                                                                         │
 │  OrderCreated → state=STOCK_RESERVING, deadline=+10s                    │
 │     tx: saga 갱신 + outbox(ReserveStock cmd)                             │
 │  StockReserved 수신 → state=SHIPPING_CREATING, deadline=+10s             │
 │     tx: saga 갱신 + outbox(CreateShipment cmd)                           │
 │  ShipmentCreated 수신 → DONE                                             │
 │  ShipmentFailed 수신 → COMPENSATING + outbox(ReleaseStock cmd)           │
 │  deadline 스캐너(1s): 초과 → attempts<N ? 명령 재발행 : 보상 또는 알림     │
 └───────────────┬──────────────────────────────────────▲─────────────────┘
                 │ commands (outbox → Kafka)            │ events
                 ▼                                      │
        ┌──────────────── Kafka ────────────────────────┴──┐
        │ stock-commands / shipment-commands / *-events    │
        └──────┬──────────────────────────────┬────────────┘
               ▼                              ▼
         product-service                shipping-service
         (명령 멱등 처리: sagaId:step)    (명령 멱등 처리)

 [도메인 경로]  PM이 FlowFailed / FlowStuck / CompensationFailed를 outbox로 발행 → 알림 파이프라인
 [흐름 진단]   FL-04 traceparent를 명령·이벤트 헤더로 전파
 [운영 경로]   SY-01: saga 상태별 개수, deadline 초과율, 명령 재발행 수, consumer lag
               saga_state{state="COMPENSATION_FAILED"} > 0 → critical
 [메타 경로]   SY-03 — PM의 deadline 스캐너 생존 heartbeat
```

## 동작 흐름

| 상황 | PM의 행동 | 알림 |
|------|----------|------|
| StockReserved 기한 초과 | ReserveStock 재발행 (멱등) | 재시도 소진 시 FlowStuck |
| ShipmentFailed | ReleaseStock 발행 (보상) | FlowFailed (warn) |
| ReleaseStock 응답 기한 초과 × N | COMPENSATION_FAILED | **F5 critical** |
| PM 크래시 | 재시작 시 saga 테이블에서 deadline 스캔 재개 | 없음 (자동 복구) |
| product 다운 | 명령이 Kafka에 대기, 기한 초과 → 재발행 → 소진 | FlowStuck 집계 + SY-02 원인 알림으로 억제 |

## 보장 속성

| 속성 | 수준 | 비고 |
|------|------|------|
| 존재 실패 감지 | 이벤트 수신 시점 | |
| 부재 실패 감지 | 기한 + 스캐너 주기 | |
| 자동 복구 | 재발행·보상을 PM이 수행 | 명령 처리 멱등 필수 |
| 상태 내구성 | saga + outbox 같은 tx | |

## 알림 실패 처리
PM이 발행한 흐름 알림은 알림 파이프라인 사다리를 따른다.

## 책임 경계 (DDD)
- 흐름 소유자 = PM(주문 컨텍스트). F3·F4·F5의 감지와 1차 자동 대응이 한곳에 있다.
- product/shipping은 명령을 받는 쪽이 된다 → 주문 컨텍스트가 하위 컨텍스트의 명령 계약에 의존(Customer-Supplier).
- 알림 컨텍스트는 여전히 Downstream. PM이 흐름 실패 "사실"을 발행할 뿐 알림 정책은 모른다.

## 트레이드오프

| 장점 | 비용 |
|------|------|
| 존재 + 부재 + 보상을 한곳에서 | 구현 복잡도 최고 (상태 머신 + 기한 + 재발행) |
| 시간 결합 없음 | PM이 흐름 로직을 독점 → 주문 서비스 비대 |
| 기한 초과 시 자동 재시도·보상 | 명령 계약 관리 |
| 흐름 상태가 명시적 — 운영 가시성 최고 | 디버깅 경로가 이벤트로 분산 |

### 흐름 방법론 3종 비교
| | FL-01 | FL-02 | FL-03 |
|---|---|---|---|
| 통신 | 동기 REST | 이벤트 | 명령 + 이벤트 |
| 흐름 주체 | 오케스트레이터 | 없음 (관찰자만) | Process Manager |
| 부재 감지 | 불필요 (응답이 옴, 단 타임아웃) | 워치독 | PM 기한 |
| 기한 초과 시 | 타임아웃 → 결과 불명 | 알림만 | 재시도·보상·알림 |
| 시간 결합 | 강함 | 없음 | 없음 |
| 복잡도 | 낮음 | 중간 | 높음 |

## 못 잡는 것 → 보완
PM 자체의 사망 → SY-01·03 / 장기 불일치 → FL-05

## 실험 가설
→ [실험 계획 · FL-03](../../04-experiments/experiment-plan.md#fl-03)
- H1: product consumer를 짧게 pause(기한 × 2 미만)하면 PM의 재발행으로 **알림 없이 자동 복구**된다. FL-02는 같은 상황에서 FlowStuck 후 RESOLVED를 낸다.
- H2: 보상 실패 주입 시 F5 커버리지 100%.
- H3: 주문 응답 p95는 FL-02와 같은 수준(시간 결합 없음), 흐름 완료 처리량은 PM 병목으로 FL-02보다 낮을 수 있다.
