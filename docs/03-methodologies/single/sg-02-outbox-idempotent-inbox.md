# SG-02. Outbox + 멱등 Inbox (id 최적화형)

> **상태**: P1 · **범주**: 단일 · **대상 실패**: F1, F2 · **id 의존도**: 높음 — 단 역할은 **멱등성 하나**로 한정

## 한 줄 요약
"순수하게 id를 활용한다면 이렇게 해야 한다"는 정답 버전. id의 역할을 **ack 왕복에서 멱등성으로** 바꾸고, 생산자 책임은 202(내구 수락)에서 끝낸다.

## 핵심 아이디어
1. **Transactional Outbox**: 비즈니스 변경과 "알림 의도"를 **같은 DB 트랜잭션**에 기록한다. dual write 공백(L6)이 사라진다.
2. **결정적 멱등키**: `idemKey = hash(aggregateId, eventType, aggregateVersion)`. 몇 번을 재발행해도 같은 키가 나온다(L3 해결).
3. **202 = 내구 수락**: 알림 서비스는 inbox에 저장한 **뒤에만** 202를 준다. 202를 받으면 생산자 책임은 끝난다. 회신 채널도, 재시도 명령도 없다(L4·L5 해결).
4. **멱등 Inbox**: `INSERT … ON CONFLICT (idem_key) DO NOTHING`. 중복 수신을 무해하게 만든다.
5. 발송 이후는 알림 서비스 내부의 **상태 머신**으로 관리한다.

## 전체 구조

```
 [도메인 경로]
 ┌──────────────────────────────────┐
 │ order-service                     │
 │  BEGIN                            │
 │   UPDATE orders …                 │
 │   INSERT outbox(idem_key, payload,│
 │                 status=NEW)       │
 │  COMMIT                           │
 │                                   │       POST /alerts (idem_key 헤더)
 │  relay (폴링 500ms, batch 100) ───┼──────────────────────────────────────┐
 │   202 → outbox.status=PUBLISHED   │◀────────── 202 Accepted ────────────┐│
 │   실패 → 백오프 후 재발행          │                                      ││
 └──────────────────────────────────┘                                      ││
   (product/shipping 동일)                                                   ││
                                                                            │▼
 ┌───────────────────────────────────────────────────────────────────────────────┐
 │ notification-service                                                           │
 │  API: INSERT inbox ON CONFLICT(idem_key) DO NOTHING → COMMIT → 202             │
 │                                                                                │
 │  inbox 상태 머신                                                               │
 │   RECEIVED ──▶ SENDING ──▶ SENT                                                │
 │                  │ 실패                                                        │
 │                  ▼                                                             │
 │               RETRY(n, next_at) ──n>max──▶ FALLBACK(Email) ──실패──▶ DLQ        │
 │                                                                                │
 │  발송 워커: SELECT … FOR UPDATE SKIP LOCKED (다중 워커 안전)                    │
 │  Notifier ─┬─ SlackNotifier ──▶ mock-slack                                     │
 │            └─ EmailNotifier ──▶ Mailpit                                        │
 │  /metrics: inbox_pending, send_fail_total, dlq_size, e2e_latency               │
 └───────────────────────────────────────────────────────────────────────────────┘

 [흐름 경로]   이 방법론은 담당하지 않음 → FL-01 또는 FL-02와 조합
 [운영 경로]   SY-01: 각 서비스의 outbox_lag(NEW 건수·최고령), 알림 서비스의 dlq_size·send_fail_rate를 Prometheus가 수집
               규칙 예: outbox_oldest_age > 60s → "relay 정체" / dlq_size > 0 → "알림 발송 실패"
               → Alertmanager가 **채널로 직접** 발송 (알림 서비스 장애 시에도 전달되도록)
 [메타 경로]   SY-03
```

## 동작 흐름

| 상황 | 결과 |
|------|------|
| 커밋 직후 크래시 (`crash-after-commit`) | outbox에 NEW로 남아 있음 → 재시작 후 relay가 발행 → **유실 없음** |
| 202 유실 (네트워크) | relay가 재발행 → inbox ON CONFLICT로 무시 → **중복 없음** |
| 알림 서비스 다운 | relay가 백오프하며 재시도, outbox에 누적 → 복구 후 일괄 소진. 생산자 비즈니스 처리는 **영향 없음** |
| 발송 후 SENT 갱신 전 크래시 | 재시작 후 재발송 → Slack 중복 1건 (at-least-once의 고유 한계 — L2) |
| Slack 장애 지속 | 알림 서비스 내부 RETRY → FALLBACK → DLQ. 생산자 무관 |

## 보장 속성

| 속성 | 수준 | 근거 |
|------|------|------|
| 유실 | **없음** (DB 내구성 범위) | outbox 원자성 + 202 이후 inbox 내구 |
| 중복 수신 | **없음** | 결정적 키 + UNIQUE |
| 중복 발송 | 드물게 있음 | 외부 부작용 비원자성 — 허용(P9) |
| 순서 | 생산자 단위로 대략 유지, 보장 안 함 | 재시도가 섞음 |
| 지연 | relay 폴링 주기 + 발송 시간 (~0.5~1s) | |

## 알림 실패 처리
[책임 사다리](../../01-problem/03-ownership-and-routing.md#4-알림-실패-처리--책임-사다리) 그대로: 내부 재시도 → fallback → DLQ → 메트릭 → 수동 replay. 생산자는 등장하지 않는다.

## 책임 경계 (DDD)
- 생산자: 사실을 outbox에 기록 + 202를 받을 때까지 넘김. **자기 사실에 대한 단방향 책임**.
- 알림: 202를 준 순간부터 전달 책임 전부.
- 진실의 원천: 발행 여부 = outbox, 전달 여부 = inbox. 각자 하나씩, 겹치지 않는다.

## 트레이드오프

| 장점 | 비용 |
|------|------|
| 브로커 없이 유실 없음 + 중복 수신 없음 | 생산자마다 outbox 테이블 + relay 코드 |
| 회신 채널 제거 → 결합 단방향 | relay 폴링 DB 부하 (서비스 수 × 주기) |
| 알림 장애가 비즈니스 처리에 영향 없음 | 이벤트 1건 = inbox row 1개 → 폭주 시 저장·발송량 그대로 |
| 구조가 단순해 설명·디버깅 쉬움 | outbox/inbox 정리(보관 기간) 작업 필요 |

## 못 잡는 것 → 보완
- 알림 폭주 (같은 문제 1,000건 → 1,000알림) → **SG-04**를 inbox 뒤에 얹음
- F4 부재 → FL-02 / F6 → SY-01·02 / F7 → SY-03

## 실험 가설
→ [실험 계획 · SG-02](../../04-experiments/experiment-plan.md#sg-02)
- H1: `crash-after-commit`, 알림 서비스 다운, 202 유실 주입에서 **유실 0, 중복 수신 0**.
- H2: 알림 서비스 30초 다운 시 비즈니스 흐름 p95는 변화가 없고, 복구 후 outbox 적체가 N초 안에 소진된다.
- H3: relay 폴링 주기를 줄일수록 알림 지연은 줄고 DB CPU는 증가한다(곡선 측정).
- H4: 장애 폭주(K3)에서 알림 수 = 실패 건수 → SG-04 비교의 기준값.
