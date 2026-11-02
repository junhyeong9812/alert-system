# SG-05. CDC 기반 Outbox

> **상태**: P2 · **범주**: 단일 · **대상 실패**: F1, F2 · **id 의존도**: 낮음 — 진행 상태는 **WAL 위치(LSN) + Kafka 오프셋**

## 한 줄 요약
SG-02/03의 outbox **relay(폴링 코드)를 없애고**, DB의 변경 로그(WAL)를 Debezium이 읽어 Kafka로 발행한다.

## 핵심 아이디어
- 생산자는 outbox 테이블에 INSERT만 한다. 발행은 인프라(Debezium)가 WAL을 따라가며 처리한다.
- 폴링이 없으므로 DB에 주기적 SELECT 부하가 없고, 커밋 직후 거의 실시간으로 발행된다.
- Debezium의 **Outbox Event Router**(SMT)가 outbox row를 토픽·키·payload로 변환한다.
- 진행 상태는 Debezium이 기록하는 WAL 위치(LSN)다. 커넥터가 재시작하면 마지막 위치부터 다시 읽는다.

## 전체 구조

```
 [도메인 경로]
 ┌────────────────────────┐
 │ order/product/shipping │  tx: biz + INSERT outbox
 └──────────┬─────────────┘
            ▼
 ┌────────────────────────┐   logical replication slot (pgoutput)
 │ PostgreSQL (WAL)       │──────────────────────────────────┐
 └────────────────────────┘                                   ▼
                                       ┌─────────────────────────────────────┐
                                       │ Kafka Connect + Debezium Postgres    │
                                       │  Outbox Event Router SMT             │
                                       │  → topic: domain-failures            │
                                       │  offset: connect-offsets 토픽 (LSN)  │
                                       └───────────────┬─────────────────────┘
                                                       ▼
                                       Kafka ──▶ notification-service (SG-03과 동일 소비 구조)
                                                       [+ SG-04 계층]

 [흐름 경로]   FL-02/03과 같은 브로커 공유
 [운영 경로]   SY-01: Debezium 커넥터 상태(RUNNING/FAILED), replication slot lag(바이트),
               MilliSecondsBehindSource, consumer lag 수집
               ★ 커넥터 정지 시 replication slot이 WAL을 붙잡아 **DB 디스크가 차오름** → 필수 감시 규칙
 [메타 경로]   SY-03
```

## 동작 흐름

| 상황 | 결과 |
|------|------|
| 커밋 직후 서비스 크래시 | WAL에 이미 기록 → 발행됨 → 유실 없음 |
| Debezium 커넥터 다운 | WAL 누적(slot 유지) → 복구 후 이어서 발행. **장기 다운 시 DB 디스크 위험** |
| Kafka 다운 | 커넥터가 재시도 → 복구 후 발행 |
| 커넥터 재시작 | 마지막 LSN부터 다시 읽음 → 일부 중복 발행 가능 |

## 보장 속성

| 속성 | 수준 | 비고 |
|------|------|------|
| 유실 | 없음 | WAL 내구성 |
| 중복 | 있음 (재시작 시) | 소비자 멱등 필요 시 SG-02 inbox 결합 |
| 순서 | 테이블·키 단위 커밋 순서 | |
| 지연 | 매우 낮음 (커밋 후 ~수십~수백 ms) | 폴링 주기 없음 |

## 알림 실패 처리
소비 측은 SG-03과 동일(재시도 토픽 → fallback → DLQ).

## 책임 경계 (DDD)
- 생산자 코드는 가장 얇다(INSERT만).
- 대신 **outbox 테이블 스키마가 곧 외부 계약**이 된다. 테이블 컬럼 변경이 소비자에 전파된다.
- 발행 책임이 애플리케이션에서 인프라 팀으로 이동한다.

## 트레이드오프

| 장점 | 비용 |
|------|------|
| relay 코드·폴링 부하 제거 | Kafka Connect + Debezium 운영 (로컬 메모리 부담 큼) |
| 발행 지연이 가장 낮음 | replication slot 관리 — 커넥터 정지 시 DB 디스크 위험 |
| 커밋 순서 보존 | DB 설정 변경 필요(`wal_level=logical`) |
| 생산자 코드 최소 | 장애 지점이 하나 더 늘어남 (커넥터) |

## 못 잡는 것 → 보완
알림 폭주 → SG-04 / F4 → FL-02 / F6 → SY-01·02 / F7 → SY-03

## 실험 가설
→ [실험 계획 · SG-05](../../04-experiments/experiment-plan.md#sg-05)
- H1: 동일 부하에서 SG-03(폴링 relay) 대비 **발행 지연이 낮고 DB CPU가 낮다.**
- H2: 커넥터를 N분 정지하면 replication slot lag이 선형 증가하고, 운영 경로 규칙이 이를 감지한다.
- H3: 전체 컨테이너 메모리 사용량이 SG-03 대비 크게 증가한다(비용 측면).
