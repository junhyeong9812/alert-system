# alert-system — 분산 서비스 실패 알림 아키텍처 탐구

> 주문 → 상품 → 배송 3개 서비스로 이루어진 작은 분산 시스템에서 발생하는 **비즈니스 실패 · 서비스 횡단(흐름) 실패 · 시스템/인프라 실패**를
> 알림 서비스가 **유실 없이, 소음 없이, 올바른 담당자에게** 전달하려면 어떤 구조가 필요한지 탐구하는 프로젝트.

## 출발 질문

기술면접에서 아래 설계를 제시했다.

> 서비스가 실패 시 `messageId`를 붙여 알림 서비스로 보내고, 알림 서비스는 이를 inbox에 기록·발송한 뒤
> 서비스와 `messageId`를 다시 주고받아 처리 여부를 확인한다. 실패가 누적되면 배치로 카운트해 서비스에게 "해당 id를 재시도하라"고 요청한다.

**그런데 알림 시스템의 안정성을 위해 굳이 id에 집착해야 할까?**
id 없이(혹은 id의 역할을 재배치해서) 안정성을 보장하는 방법은 무엇이고, 각각 어떤 트레이드오프가 있으며,
k6로 실패를 섞어 부하를 걸었을 때 실제로 어떻게 동작하는가 — 이것이 이 프로젝트의 질문이다.

## 목표

1. 최초 설계의 문제점과 그 이유를 명확히 한다 (특히 id 정합성이 만드는 문제 루프).
2. 실패 유형을 분리하고, 유형별 **감지 · 전달 · 대응 담당**을 정한다.
3. 해결 방법론을 **단일 / 흐름 / 시스템** 범주로 리스트업하고, 방법론마다 운영 경로를 포함한 전체 구조를 그린다.
4. k6 + 장애 주입으로 방법론을 비교 실험한다 (정상 동작 여부 + 부하 특성).
5. 실험 결과를 근거로 **최종 통합 구조**를 확정한다.

## 시스템 한눈에 보기

```
           k6 (부하 + X-Fault 장애 주입)
                    │
     ┌──────────────┼──────────────────────────────────────┐
     ▼              ▼                                      │
 ┌────────┐    ┌──────────┐    ┌──────────┐                │
 │ order  │───▶│ product  │───▶│ shipping │   (흐름: 동기 오케스트레이션 | 이벤트 코레오그래피)
 └───┬────┘    └────┬─────┘    └────┬─────┘                │
     │ 실패 사실     │              │                       │
     └──────────────┴──────┬───────┘                       │
                           ▼                               │
                 ┌───────────────────┐     ┌──────────────────────────┐
                 │ notification      │◀────│ 운영 경로 (외부 모니터링)│
                 │  dedup·묶음·라우팅│     │ Prometheus/Alertmanager… │
                 │  Notifier 추상화  │     └──────────────────────────┘
                 └───┬──────────┬────┘
                     ▼          ▼
               mock-slack    mailpit(email)
```

상세: [인프라 구조](docs/02-infrastructure/infra-architecture.md)

## 문서 지도

### 1. 문제 정의
| 문서 | 내용 |
|------|------|
| [최초 설계 리뷰](docs/01-problem/01-initial-design-review.md) | messageId 왕복 핸드셰이크 설계의 문제점, 왜 문제인가, **id 정합성 문제 루프** |
| [실패 유형 분류](docs/01-problem/02-failure-taxonomy.md) | F1~F7 실패 유형, 신호 형태(존재/부재/상태), 경계 사례 |
| [담당과 라우팅](docs/01-problem/03-ownership-and-routing.md) | 유형별 감지·전달·대응 담당, DDD 컨텍스트 경계, 알림 실패 처리 책임 |

### 2. 인프라
| 문서 | 내용 |
|------|------|
| [인프라 구조](docs/02-infrastructure/infra-architecture.md) | 4개 서비스 + 인프라 구성, docker compose 프로필, 장애 주입 지점 |

### 3. 방법론 → [방법론 지도 (커버리지 · 우선순위)](docs/03-methodologies/README.md)

**단일 서비스 실패** — 한 서비스 안에서 발생한 실패 사실을 알림까지 전달
| ID | 방법론 | 상태 |
|----|--------|------|
| [SG-01](docs/03-methodologies/single/sg-01-id-ack-handshake.md) | messageId 왕복 핸드셰이크 (최초 설계) | 기준선 |
| [SG-02](docs/03-methodologies/single/sg-02-outbox-idempotent-inbox.md) | Outbox + 멱등 Inbox (id 최적화형) | P1 |
| [SG-03](docs/03-methodologies/single/sg-03-broker-at-least-once.md) | 브로커 at-least-once + 오프셋 | P1 |
| [SG-04](docs/03-methodologies/single/sg-04-fingerprint-aggregation.md) | Fingerprint 묶음 처리 (문제 단위 dedup) | P1 |
| [SG-05](docs/03-methodologies/single/sg-05-cdc-outbox.md) | CDC 기반 Outbox | P2 |
| [SG-06](docs/03-methodologies/single/sg-06-pull-polling.md) | Pull 폴링 (커서) | P2 |
| [SG-07](docs/03-methodologies/single/sg-07-log-based-alerting.md) | 로그 기반 알림 | P2 |

**흐름(서비스 횡단) 실패** — 주문→상품→배송을 가로지르는 실패
| ID | 방법론 | 패턴 | 상태 |
|----|--------|------|------|
| [FL-01](docs/03-methodologies/flow/fl-01-sync-orchestration.md) | 동기 오케스트레이션 실패 전파 + 보상 | 동기 오케스트레이션 | P1 |
| [FL-02](docs/03-methodologies/flow/fl-02-choreography-watchdog.md) | 코레오그래피 + Saga 부재 감지 워치독 | 이벤트 코레오그래피 | P1 |
| [FL-03](docs/03-methodologies/flow/fl-03-process-manager.md) | 이벤트 기반 오케스트레이터 (Process Manager) | 비동기 오케스트레이션 | P2 |
| [FL-04](docs/03-methodologies/flow/fl-04-trace-correlation.md) | traceId 전파 · 상관 분석 | 공통 | P1 |
| [FL-05](docs/03-methodologies/flow/fl-05-reconciliation-sweeper.md) | Reconciliation Sweeper (사후 대조) | 공통 | P2 |

**시스템/인프라 실패** — 서비스가 스스로 보고할 수 없는 실패
| ID | 방법론 | 상태 |
|----|--------|------|
| [SY-01](docs/03-methodologies/system/sy-01-level-triggered-metrics.md) | Level-triggered 메트릭 (Prometheus + Alertmanager) | P1 |
| [SY-02](docs/03-methodologies/system/sy-02-blackbox-probing.md) | Blackbox · Health 프로빙 | P1 |
| [SY-03](docs/03-methodologies/system/sy-03-dead-mans-switch.md) | Dead man's switch (감시자 감시) | P1 |

**통합**
| ID | 방법론 | 상태 |
|----|--------|------|
| [IN-01](docs/03-methodologies/integrated/in-01-integrated-architecture.md) | 단일 + 흐름 + 시스템 통합 구조 (슬롯형 후보) | 가설 — 실험 후 확정 |

### 4. 실험
| 문서 | 내용 |
|------|------|
| [실험 계획](docs/04-experiments/experiment-plan.md) | 연구 질문, 장애 주입 카탈로그, k6 시나리오, 측정 지표, 방법론별 실험 계획 |

### 5. 최종 구조
실험 완료 후 `docs/05-final/`에 작성 예정.

## 우선순위 표기 규칙

| 표기 | 의미 |
|------|------|
| **기준선** | 비교의 기준. 실험하되 채택 후보는 아님 |
| **P1** | 반드시 실험 — 최종 구조 후보 |
| **P2** | 여력이 있으면 실험 — 특정 조건에서의 대안 |
| **가설** | 실험 전 제안. 실험 결과로 확정·수정 |

모든 우선순위는 **실험 전 가설**이다. 실험 결과에 따라 바뀔 수 있으며, 바뀐 근거는 실험 결과 문서에 남긴다.

## 진행 상태

- [x] 문제 정의 · 실패 유형 · 담당
- [x] 방법론 리스트업 · 방법론별 구조
- [x] 실험 계획
- [ ] 구현 명세 합의 (언어/스택, 브로커, 실험 범위)
- [ ] 인프라 + 서비스 + 실험 하네스 구현
- [ ] 실험 실행 · 결과 기록
- [ ] 최종 구조 확정
