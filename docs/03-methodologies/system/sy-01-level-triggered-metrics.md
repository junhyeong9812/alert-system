# SY-01. Level-triggered 메트릭 (Prometheus + Alertmanager)

> **상태**: P1 · **범주**: 시스템 · **대상 실패**: F6 (주력), F2 (에러율), F1 (비율 이상), F7 (부분) · **id 의존도**: 없음 — **상태 평가**

## 한 줄 요약
서비스는 카운터·게이지를 `/metrics`로 노출만 하고, Prometheus가 **주기적으로 수집(pull)**해 규칙을 평가한다. 알림은 "이벤트가 왔다"(edge)가 아니라 **"지금 이 조건이 참이다"(level)**이므로, 알림 하나를 잃어도 다음 평가에서 다시 발화한다.

## 핵심 아이디어
- **pull**: 죽은 서버는 push할 수 없지만, 수집 실패(`up == 0`)는 Prometheus가 관측한다.
- **level-triggered**: 상태가 지속되는 한 계속 firing. 전달 실패·유실에 대해 **자기 복구**적이다. → "id 없이 안정성"의 가장 강한 답.
- **Alertmanager**가 그룹핑(group_by)·억제(inhibit)·침묵(silence)·재알림(repeat_interval)·해소(resolved)를 담당한다 — SG-04가 도메인 이벤트에 하는 일을 메트릭에 한다.
- 개별 맥락(어떤 주문)은 없다. 카디널리티 때문에 orderId를 라벨로 쓰면 안 된다. 맥락은 exemplar(trace_id)로 연결한다(FL-04).

## 전체 구조

```
 [운영 경로]
 ┌────────────────┐ ┌────────────────┐ ┌────────────────┐ ┌──────────────────────┐
 │ order /metrics │ │product /metrics│ │shipping/metrics│ │notification /metrics │
 └───────▲────────┘ └───────▲────────┘ └───────▲────────┘ └──────────▲───────────┘
         │ scrape 5s        │                  │                     │
 ┌───────┴──────────────────┴──────────────────┴─────────────────────┴─────────────┐
 │ Prometheus  (+ postgres-exporter, kafka-exporter, cAdvisor)                       │
 │  규칙 (평가 15s):                                                                 │
 │   ServiceDown        up{job=~"order|product|shipping"} == 0           for 30s  critical
 │   HighErrorRate      rate(http_server_requests{status=~"5.."}[1m]) / rate(…) > 0.05 for 1m warn
 │   BizFailureSpike    rate(biz_failure_total[5m]) > 3 × rate(biz_failure_total[1h] offset 1d) warn
 │   DbPoolExhausted    db_pool_pending > 0                               for 1m  critical
 │   OutboxLag          outbox_oldest_age_seconds > 60                    for 1m  warn
 │   AlertDlqNonEmpty   alert_dlq_size > 0                                         warn
 │   ConsumerLag        kafka_consumergroup_lag > 1000                    for 2m  warn
 │   Watchdog           vector(1)  (항상 firing — SY-03용)                          none
 └──────────────────────────────────────┬──────────────────────────────────────────┘
                                        ▼
 ┌──────────────────────────────────────────────────────────────────────────────────┐
 │ Alertmanager                                                                      │
 │  group_by: [alertname, service]   group_wait 10s  group_interval 1m  repeat 30m   │
 │  inhibit: ServiceDown(service=X) ⟶ HighErrorRate/FlowFailed(service=X) 억제        │
 │           PostgresDown ⟶ DbPoolExhausted, HighErrorRate 억제                        │
 │  route:                                                                           │
 │   ├ alertname=Watchdog        → dead-man-switch (SY-03)                            │
 │   ├ alertname=AlertDlqNonEmpty → 채널 직접 (Email)  ← 알림 서비스 우회              │
 │   ├ severity=critical         → 변형 A: 채널 직접 / 변형 B: notification-service    │
 │   └ default                   → notification-service webhook                       │
 └──────────────────────────────────────────────────────────────────────────────────┘

 [도메인/흐름 경로]  이 방법론은 담당하지 않음 — SG·FL 계열과 조합
 [메타 경로]        SY-03 (Watchdog → dead-man-switch)
```

### 변형 A vs B — F6 알림을 알림 서비스로 합류시킬 것인가

```
 변형 A (분리)                               변형 B (합류)
 Alertmanager ──▶ Slack/Email 직접           Alertmanager ──webhook──▶ notification-service ──▶ Slack/Email
   + 알림 서비스 장애와 무관하게 전달          + 라우팅·포맷·담당 정책을 한곳에서 관리
   − 라우팅 정책이 두 곳 (AM + 알림 서비스)    − 알림 서비스 장애 시 F6 전달도 막힘
```
→ 실험에서 "알림 서비스 다운 + 서비스 다운 동시 발생" 시나리오로 비교한다.

## 동작 흐름

| 상황 | 결과 |
|------|------|
| shipping OOM kill | 다음 scrape 실패 → `up=0` → 30s 지속 → ServiceDown critical |
| 커넥션 풀 고갈 (서비스는 살아 있음) | DbPoolExhausted + HighErrorRate → 억제로 원인만 |
| 알림 webhook 1회 실패 | Alertmanager 재시도 + 다음 group_interval에 재발송 (level이라 상태 유지) |
| 장애 해소 | 조건 거짓 → resolved 알림 |
| Prometheus 다운 | 모든 규칙 평가 중단 → **조용해짐** → SY-03이 잡음 |

## 보장 속성

| 속성 | 수준 | 비고 |
|------|------|------|
| 유실 | 자기 복구적 | 상태가 지속되는 한 재발화 |
| 중복 | Alertmanager가 그룹·repeat로 제어 | |
| 짧은 장애 | **놓칠 수 있음** | scrape 주기보다 짧은 스파이크, `for` 기간보다 짧은 장애 |
| 지연 | scrape + 평가 + for + group_wait (30s~2m) | |
| 개별 맥락 | 없음 | exemplar로 trace 연결 |

## 알림 실패 처리
Alertmanager의 webhook 재시도 + level 특성. 알림 서비스 DLQ 적재량도 이 경로가 감시한다(AlertDlqNonEmpty → 알림 서비스 우회).

## 책임 경계 (DDD)
- 서비스: 메트릭을 정직하게 노출할 책임 (도메인 카운터 포함: `biz_failure_total{code}`).
- 운영/플랫폼 팀: 규칙·임계·라우팅 소유. F6 1차 대응은 on-call.
- 도메인 의미가 있는 임계(재고 부족 비율 등)는 도메인 팀과 합의.

## 트레이드오프

| 장점 | 비용 |
|------|------|
| 죽은 서버 감지 (pull) | 개별 건 맥락 없음 |
| 자기 복구 — 전달 실패에 강함 | 짧은 스파이크 놓침 (해상도 = 주기) |
| 업계 표준, 생태계 풍부 | 카디널리티 관리 |
| 그룹·억제·해소를 무료로 | 감지 지연 (for 기간) |

## 못 잡는 것 → 보완
개별 비즈니스 실패의 맥락 → SG 계열 / 부재 흐름의 개별 건 → FL-02 / Prometheus 자신 → SY-03

## 실험 가설
→ [실험 계획 · SY-01](../../04-experiments/experiment-plan.md#sy-01)
- H1: 서비스 kill 주입 시 **scrape + for + group_wait 이내**에 ServiceDown이 도달한다.
- H2: webhook 대상(알림 서비스)을 일시 중지해도 장애가 지속되면 **복구 후 알림이 도달**한다(자기 복구).
- H3: DB 다운 시 억제 규칙 적용 전/후 알림 수 비교 — 적용 후 원인 알림만 남는다.
- H4: 변형 A/B에서 "알림 서비스 + shipping 동시 다운" 시 B는 F6 알림이 지연·유실, A는 정상 도달.
