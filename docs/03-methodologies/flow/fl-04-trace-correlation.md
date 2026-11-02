# FL-04. traceId 전파 · 상관 분석

> **상태**: P1 (항상 켬) · **범주**: 흐름 (공통 계층) · **대상 실패**: F3 (진단), F4·F5 (진단 보조) · **id 의존도**: 있음 — 단 **전달 확인이 아니라 인과 추적**용

## 한 줄 요약
OpenTelemetry로 모든 요청·메시지에 `traceparent`를 전파해서, 알림이 가리키는 실패가 **어느 흐름의 어느 구간에서** 났는지 바로 볼 수 있게 한다. 알림을 "보내는" 방법이 아니라 알림을 **"쓸모 있게"** 만드는 계층이다.

## 핵심 아이디어
- 최초 설계의 문제 P11(msgId와 traceId 혼동)을 푼다. **msgId는 알림 메시지의 정체성, traceId는 비즈니스 흐름의 정체성**이다.
- 동기 구간: HTTP 헤더 `traceparent` 자동 전파 (OTel 계측).
- 비동기 구간: Kafka 메시지 헤더에 `traceparent`를 넣고 소비자가 이어 붙인다(span link 또는 parent).
- 알림 payload에 `traceIds[]`(샘플)를 담고, Slack 메시지에 Jaeger 링크를 붙인다.
- 로그(SG-07)·메트릭 exemplar(SY-01)에도 trace_id를 남겨 **세 신호(trace·log·metric)를 하나로 연결**한다.

## 전체 구조

```
 [흐름 경로]
 k6 ──▶ order ──(traceparent)──▶ product ──(traceparent)──▶ shipping          (동기 FL-01)
 k6 ──▶ order ─[Kafka 헤더 traceparent]─▶ product ─[헤더]─▶ shipping          (비동기 FL-02/03)
          │            │             │
          └─── OTLP ───┴──────┬──────┘
                              ▼
                    ┌──────────────────┐        ┌────────┐
                    │ OTel Collector   │──────▶ │ Jaeger │  trace 조회 UI
                    │  tail sampling:  │        └────────┘
                    │  에러 trace 100%  │
                    │  정상 trace 10%   │
                    └──────────────────┘

 [알림 연결]
   FailureOccurred{…, traceId} ──▶ notification-service ──▶ Slack:
   "[WARN] FlowFailed order#1832 shipping step — trace: http://localhost:16686/trace/4bf9…"

 [운영 경로]   SY-01 메트릭에 exemplar(trace_id) 부착 → Grafana에서 지연 급증 지점 → 해당 trace
               Collector 자체의 drop/queue 메트릭 수집
 [메타 경로]   해당 없음 (진단 계층 — 이 계층이 죽어도 알림 전달은 계속됨)
```

## 동작 흐름

| 질문 | trace 없이 | trace 있음 |
|------|-----------|-----------|
| "order#1832 실패, 어디서?" | 3개 서비스 로그를 시간으로 맞춰 grep | trace 1개에서 실패 span 확인 |
| "shipping이 느린가, 네트워크인가?" | 추측 | span 간 간격 vs span 내부 시간 |
| "FlowStuck 128건의 공통점?" | 개별 조사 | 샘플 trace들이 같은 지점에서 끊김 |
| "보상이 실행됐나?" | DB 조회 | 보상 span 존재 여부 |

## 보장 속성
- **전달 보장과 무관.** trace는 샘플링될 수 있고 유실돼도 알림은 간다. 알림 로직이 trace에 의존하면 안 된다.
- 에러 trace는 tail sampling으로 100% 보존을 목표로 한다.

## 책임 경계 (DDD)
- 전 서비스 공통 인프라 관심사(cross-cutting). 도메인 코드에서는 계측 라이브러리가 처리한다.
- 알림 컨텍스트는 traceId를 **불투명한 값**으로 전달만 한다(해석하지 않음).

## 트레이드오프

| 장점 | 비용 |
|------|------|
| 흐름 실패 진단 시간 대폭 단축 | 계측 오버헤드 (CPU·지연 소폭) |
| trace·log·metric 연결 | Collector·저장소 리소스 |
| 비동기 흐름도 하나로 볼 수 있음 | 비동기 전파는 수동 작업이 필요할 수 있음 |

## 못 잡는 것
감지 자체는 하지 않는다. 다른 방법론이 감지한 실패를 **진단 가능하게** 할 뿐이다.

## 실험 가설
→ [실험 계획 · FL-04](../../04-experiments/experiment-plan.md#fl-04)
- H1: OTel 계측 on/off에서 주문 p95 차이가 작다(오버헤드 정량화).
- H2: 흐름 실패 알림의 traceId 샘플이 Jaeger에서 **100% 조회 가능**하다(tail sampling 검증).
- H3: 비동기 구간(FL-02)에서도 trace가 끊기지 않는다.
