# SG-04. Fingerprint 묶음 처리 (문제 단위 dedup)

> **상태**: P1 · **범주**: 단일 (계층 — SG-02/03 위에 얹음) · **대상 실패**: F1, F2 · **id 의존도**: 없음 — 정체성은 **내용에서 계산**

## 한 줄 요약
메시지의 정체성(msgId) 대신 **문제의 정체성(fingerprint)**으로 알림을 묶는다. 같은 문제 1,000건은 알림 1건("342건 발생, 예시 3개")이 된다.

## 핵심 아이디어
- `fingerprint = hash(service, category, errorCode, severity [, 대상 리소스])` — 메시지 내용에서 계산하므로 별도 id 관리가 없다.
- fingerprint별로 **묶음 윈도우** 동안 count · firstSeen · lastSeen · samples를 누적하고, 윈도우가 닫히면 요약 알림 1건을 보낸다.
- 알림을 **이벤트가 아니라 상태(firing / resolved)**로 다룬다. 같은 fingerprint가 계속 발생하면 재알림 주기마다 "지속 중"을, 멈추면 "해소"를 보낸다.
- critical은 첫 건을 **즉시 발송**하고(묶음 우회), 이후 건만 묶는다.
- 하위 증상 억제: 상위 원인 fingerprint(예: DB 다운)가 firing이면 관련 증상 fingerprint 발송을 억제한다.

## 전체 구조

```
 [도메인 경로]  (입력은 SG-02 inbox 또는 SG-03 consumer — 유실·중복 수신 처리는 하부 계층 책임)

   실패 사실 ──▶ ┌─────────────────────────────────────────────────────────────┐
                 │ notification-service                                         │
                 │  ① 정규화 (ACL): FailureOccurred → Alert 후보                │
                 │  ② fingerprint 계산                                         │
                 │  ③ 집계 저장소 (notification_db 또는 메모리+스냅샷)          │
                 │     fp ─▶ {count, firstSeen, lastSeen, samples[≤5],          │
                 │            traceIds[≤5], state: FIRING/RESOLVED, lastNotified}│
                 │  ④ 억제 규칙: 원인 fp FIRING → 증상 fp 발송 보류             │
                 │  ⑤ 스케줄러 (1s tick)                                        │
                 │     - critical 첫 건 → 즉시                                  │
                 │     - 윈도우 종료 → 요약 발송                                 │
                 │     - FIRING 지속 → repeat_interval마다 재알림                │
                 │     - lastSeen + resolve_timeout 경과 → RESOLVED 발송         │
                 │  ⑥ Notifier (Slack/Email) + retry/fallback/DLQ (하부와 공통)  │
                 └─────────────────────────────────────────────────────────────┘

   Slack 예:
   [WARN] shipping-service ADDRESS_INVALID — 342건 (14:02:10 ~ 14:03:10)
          예: order#1832, order#1840, order#1855  trace: 4bf9…, 9a1c…

 [흐름 경로]   FL-02 워치독의 "흐름 정지" 알림도 fp = hash(flow, stuckStep)로 같은 집계를 탐
 [운영 경로]   SY-01: fingerprint_firing_count, suppressed_total, window_flush_latency 노출
               Alertmanager가 하는 일(group_by/inhibit/repeat)과 개념이 같음 → "도메인 이벤트용 Alertmanager"
 [메타 경로]   SY-03
```

## 동작 흐름

| 상황 | SG-02 단독 | SG-02 + SG-04 |
|------|-----------|---------------|
| 같은 예외 1,000건/분 | Slack 1,000건 | 윈도우당 1건 + 재알림 |
| 서로 다른 3종 예외 | 각각 개별 건수만큼 | 3건 |
| DB 다운 → 3개 서비스 예외 폭주 | 수천 건 | 원인 1건 (증상 억제) |
| critical 1건 | 즉시 1건 | 즉시 1건 (우회) |
| 문제 해소 | 신호 없음 | RESOLVED 1건 |

## 보장 속성

| 속성 | 수준 | 비고 |
|------|------|------|
| 유실 | 하부 계층에 의존 | **집계 상태가 메모리뿐이면** 크래시 시 윈도우 내용 유실 → DB 저장 또는 하부 재소비로 복원 |
| 사람이 보는 중복 | **문제 단위로 1회 + 재알림** | |
| 개별 건 추적 | 샘플만 | 전체 목록은 inbox/로그에서 조회 |
| 지연 | 윈도우 길이만큼 (critical 제외) | |

## 알림 실패 처리
요약 알림 1건이 실패해도 count는 집계 저장소에 남아 있다. 재시도 시 **최신 count로 다시 만든 요약**을 보낸다(오래된 요약을 그대로 재전송하지 않음). 상태 기반이라 자연스럽게 수렴한다.

## 책임 경계 (DDD)
- fingerprint 규칙(무엇을 같은 문제로 볼 것인가)은 **알림 컨텍스트의 정책**이다. 생산자는 errorCode·category를 정확히 담아 보낼 책임만 있다.
- 생산자 이벤트 스키마에 `errorCode`, `category`가 없으면 fingerprint 품질이 떨어진다 → Published Language 설계에 포함.

## 트레이드오프

| 장점 | 비용 |
|------|------|
| 알림 피로 해결 — 알림 수 ∝ 문제 수 | 윈도우만큼 지연 (critical 우회로 완화) |
| 해소(resolved) 신호 제공 | 집계 상태의 내구성 관리 필요 |
| id 관리 없음 — 내용에서 계산 | fingerprint 설계가 틀리면 다른 문제가 합쳐지거나(과소) 같은 문제가 갈라짐(과다) |
| 억제로 원인 알림만 남김 | 억제 규칙 오설정 시 진짜 알림을 숨길 위험 |

## 못 잡는 것 → 보완
단독 구조가 아니다. 하부(SG-02/03)가 유실·중복 수신을, 흐름·시스템 범주는 각 방법론이 담당한다.

## 실험 가설
→ [실험 계획 · SG-04](../../04-experiments/experiment-plan.md#sg-04)
- H1: 장애 폭주(K3)에서 알림 수가 SG-02 대비 **두 자릿수 배 이상** 감소하고, 주입한 문제 종류는 **전부** 알림에 나타난다(커버리지 100%).
- H2: critical 첫 건 지연은 SG-02와 같고, warn 알림 지연은 윈도우 길이에 수렴한다.
- H3: 집계 상태를 메모리만 쓰는 변형은 크래시 시 해당 윈도우 알림을 잃고, DB 저장 변형은 잃지 않는다.
