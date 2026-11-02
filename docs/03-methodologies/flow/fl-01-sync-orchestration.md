# FL-01. 동기 오케스트레이션 — 실패 전파 + 보상

> **상태**: P1 · **범주**: 흐름 · **패턴**: 동기 오케스트레이션 (REST) · **대상 실패**: F3, F5 · **id 의존도**: 낮음 — 흐름 정체성은 **orderId + traceId**

## 한 줄 요약
order-service가 오케스트레이터로서 product → shipping을 **동기 호출**하고, 실패는 호출 스택을 타고 오케스트레이터에 돌아온다. 오케스트레이터가 보상을 실행하고 **흐름 단위 실패 사실**을 발행한다.

## 핵심 아이디어
- 동기 흐름에서는 실패가 **존재 신호**로 호출자에게 돌아온다. 흐름 전체를 아는 주체(오케스트레이터)가 있으므로 감지는 쉽다.
- 어려운 부분은 **결과 불명(타임아웃)**과 **보상 실패**다.
- 오케스트레이터는 흐름 상태를 자기 DB(`order_saga`)에 단계별로 기록한다. 크래시 후에도 "어디까지 했는지" 알 수 있다.
- 알림은 서비스별 예외가 아니라 **흐름 실패 1건**으로 보낸다: "order#1832: 재고 차감 완료 → 배송 생성 실패 → 재고 복원 완료".

## 전체 구조

```
 [흐름 경로]
            k6
             │ POST /orders
             ▼
 ┌──────────────────────────────────────────────────────────────────────────┐
 │ order-service (오케스트레이터)                                             │
 │  order_saga: {orderId, step, status, traceId, updatedAt}                   │
 │                                                                            │
 │  ① tx: order=PENDING, saga=STARTED                                        │
 │  ② POST product/reserve ── timeout 2s, retry 1, circuit breaker ──┐        │
 │  ③ POST shipping/create ── timeout 2s, retry 1, circuit breaker ──┼──┐     │
 │  ④ 성공: tx: order=CONFIRMED, saga=DONE                            │  │     │
 │  ⑤ 실패: 보상 역순 실행 (product/release)                           │  │     │
 │      tx: order=FAILED, saga=COMPENSATED | COMPENSATION_FAILED      │  │     │
 │           + outbox(FlowFailed{orderId, failedStep, compensation})  │  │     │
 └──────────────────────────────────────────────┬─────────────────────│──│─────┘
                                                │                     ▼  ▼
                                                │          product-service  shipping-service
                                                │          (멱등 API: Idempotency-Key = orderId:step)
                                                ▼
 [도메인 경로]  outbox ──(SG-02 또는 SG-03)──▶ notification-service ──▶ Slack/Email
               FlowFailed는 F3(warn), COMPENSATION_FAILED는 F5(critical)

 [흐름 진단]   FL-04: traceId가 order → product → shipping 전 구간에 전파 → Jaeger에서 실패 구간 확인

 [운영 경로]   SY-01: 하위 호출 에러율·지연(http_client_requests), circuit breaker 상태(OPEN 전환),
                     saga 상태별 개수(saga_status{status="COMPENSATION_FAILED"}),
                     STARTED 상태로 오래 머문 saga 수(오케스트레이터 크래시 감지)
               SY-02: 3개 서비스 /health 프로빙 → 하위 서비스 사망은 FlowFailed 폭증과 **억제 관계**
 [메타 경로]   SY-03
```

## 동작 흐름

| 상황 | 감지 | 처리 | 알림 |
|------|------|------|------|
| product 재고 부족 (F1) | 409 응답 | 보상 불필요 (첫 단계) | F1으로 분류 (흐름 실패 아님) |
| shipping 5xx | 오케스트레이터 | product/release 보상 | FlowFailed (warn) |
| shipping **타임아웃** | 오케스트레이터 | **결과 불명** → shipping 상태 조회 후 판단, 조회 실패 시 `UNKNOWN` 기록 | FlowUncertain (warn) + FL-05 대조 대상 |
| 보상(release) 실패 | 오케스트레이터 | 재시도 N회 → 소진 시 COMPENSATION_FAILED | **F5 critical** |
| 오케스트레이터 크래시 (③ 도중) | saga가 STARTED로 정체 | 재시작 시 STARTED + 오래된 saga 재개(복구 스캐너) | 복구 실패 시 FlowFailed |
| shipping 서비스 다운 | circuit OPEN | 즉시 실패 + 보상 | FlowFailed 폭증 → SY-02 "shipping down"이 억제 |

### 타임아웃의 불확실성
```
 order ── POST shipping/create ──▶ shipping: 배송 생성 ✔ (커밋)
       ◀──────── ✕ 응답 유실 / 2s 초과
 order: "실패"로 판단 → 보상(재고 복원) → 그런데 배송은 생성돼 있음 → 불일치
```
→ 대응: 하위 API를 **멱등**으로 만들고(`Idempotency-Key: orderId:step`), 타임아웃 시 **재시도 또는 상태 조회**로 결과를 확정한 뒤 보상한다. 확정 불가면 UNKNOWN으로 남기고 사후 대조(FL-05).

## 보장 속성

| 속성 | 수준 | 비고 |
|------|------|------|
| 흐름 실패 감지 | 즉시 (호출 응답 시점) | |
| 흐름 실패 알림 유실 | 없음 | saga 상태 + outbox를 같은 tx로 |
| 결과 불명 | 존재 | 멱등 + 상태 조회 + FL-05로 축소 |
| 진단 | traceId로 전 구간 확인 | FL-04 |

## 알림 실패 처리
흐름 실패 사실은 outbox로 넘기고, 이후는 단일 범주 방법론(SG-02/03)의 사다리를 따른다.

## 책임 경계 (DDD)
- **흐름 소유자 = 오케스트레이터 소유 팀(주문팀).** F3·F5 알림의 1차 대응자.
- product/shipping은 자기 단계의 실패만 응답으로 돌려준다. 흐름 알림을 직접 보내지 않는다 → 중복 알림 방지.
- 오케스트레이터는 하위 서비스의 API에 결합된다(동기 호출의 본질적 비용).

## 트레이드오프

| 장점 | 비용 |
|------|------|
| 흐름 실패 감지가 즉시·확실 | **시간적 결합** — 하위 지연이 주문 응답 지연에 그대로 합산 |
| 흐름 상태가 한곳(saga 테이블)에 모임 | 하위 장애가 주문 실패로 직결 (가용성 = 곱) |
| 보상 순서·로직이 명확 | 타임아웃의 결과 불명 |
| 디버깅이 쉬움 (호출 스택 + trace) | 오케스트레이터가 단일 장애점·병목 |

## 못 잡는 것 → 보완
- 결과 불명 상태의 최종 확정 → FL-05
- 하위 서비스 자체의 사망 원인 → SY-01·02
- 서비스 내부의 비즈니스 실패 개별 전달 → SG 계열

## 실험 가설
→ [실험 계획 · FL-01](../../04-experiments/experiment-plan.md#fl-01)
- H1: shipping에 `latency` 주입 시 주문 p95가 **주입 지연만큼 증가**한다(시간 결합). FL-02와 대비.
- H2: shipping 타임아웃 주입에서 멱등 + 상태 조회가 없으면 재고 불일치가 발생하고, 있으면 UNKNOWN 건만 남는다.
- H3: 보상 실패 주입은 전부 critical 알림으로 도달한다(F5 커버리지 100%).
- H4: shipping 다운 시 FlowFailed 폭증 대신 원인 알림(shipping down) 1건 + 억제된 증상으로 수렴한다(SY-02 + SG-04 결합 시).
