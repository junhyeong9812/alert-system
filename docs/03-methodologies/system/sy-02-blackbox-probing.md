# SY-02. Blackbox · Health 프로빙

> **상태**: P1 · **범주**: 시스템 · **대상 실패**: F6 (주력), F2·F7 (부분) · **id 의존도**: 없음

## 한 줄 요약
서비스 **바깥에서** 사용자처럼 요청을 보내 응답을 확인한다. 내부 메트릭(SY-01)이 "서비스가 자기 상태를 어떻게 말하는가"라면, 프로빙은 **"밖에서 보면 실제로 되는가"**다.

## 핵심 아이디어
- **liveness vs readiness 분리**
  - `/health/live`: 프로세스가 살아 있나 (의존성 확인 안 함)
  - `/health/ready`: 요청을 처리할 수 있나 (DB·Kafka 연결 확인)
  - live는 OK인데 ready가 실패 → "살아 있지만 일 못함" (커넥션 풀 고갈, DB 단절)
- **synthetic 트랜잭션**: 주기적으로 실제 주문 흐름을 테스트 데이터로 한 번 돌린다(`X-Synthetic: true`). 각 서비스의 health가 모두 OK여도 **흐름이 실제로 끝까지 도는지** 확인한다.
- 내부 계측이 거짓말하거나(메트릭 수집 스레드는 살았는데 요청 처리 스레드는 데드락) 계측이 없는 경우에도 동작한다.

## 전체 구조

```
 [운영 경로]
 ┌──────────────────────────────────────────────────────────────────────────┐
 │ Blackbox Exporter (Prometheus가 15s마다 트리거)                            │
 │  probe http_2xx  → order:8081/health/ready, product…, shipping…, notification…
 │  probe http_2xx  → order:8081/health/live …                              │
 │  probe tcp       → postgres:5432, kafka:9092                              │
 └───────────────────────────────┬──────────────────────────────────────────┘
                                 ▼ probe_success, probe_duration_seconds
 ┌──────────────────────────────────────────────────────────────────────────┐
 │ synthetic-prober (경량 컨테이너, 1분 주기)                                  │
 │  POST /orders {synthetic:true} → 30s 내 상태 CONFIRMED 확인                │
 │  결과 → synthetic_flow_success, synthetic_flow_duration_seconds (메트릭)   │
 │  (서비스는 synthetic 주문을 실제 처리하되 알림 파이프라인에서는 제외/태깅)    │
 └───────────────────────────────┬──────────────────────────────────────────┘
                                 ▼
 Prometheus 규칙:
   Unreachable     probe_success{probe="live"} == 0          for 30s  critical
   NotReady        probe_success{probe="ready"} == 0 and live == 1 for 1m  critical
   SlowProbe       probe_duration_seconds > 1                for 2m   warn
   SyntheticFail   synthetic_flow_success == 0               for 2m   critical
        └─▶ Alertmanager (SY-01과 동일 라우팅·억제)

 [도메인/흐름 경로]  synthetic 흐름은 FL-02 워치독 기대 규칙도 함께 검증 (워치독이 synthetic 주문을 놓치면 워치독 이상)
 [메타 경로]        SY-03
```

## 동작 흐름

| 상황 | SY-01 (내부 메트릭) | SY-02 (외부 프로빙) |
|------|-------------------|-------------------|
| 프로세스 사망 | up=0 | Unreachable |
| DB 단절 (프로세스 생존) | 에러율 상승 (요청이 있어야 보임) | **NotReady** (요청 없어도 보임) |
| 요청 처리 스레드 데드락 | 메트릭은 정상일 수 있음 | probe 타임아웃 |
| 각 서비스 정상, 흐름 설정 오류 | 보이지 않음 | **SyntheticFail** |
| 트래픽 0인 새벽 장애 | 에러율 계산 불가 | 프로빙은 계속 |

## 보장 속성
- 감지 지연: 프로빙 주기 + for (30s~2m)
- 트래픽과 무관하게 동작 — **실사용 트래픽이 없어도 감지**
- 프로버 자신이 죽으면 `probe_success`가 사라짐 → `absent()` 규칙으로 감지

## 책임 경계 (DDD)
- 서비스: live/ready 의미를 정확히 구현할 책임 (ready에 무거운 검사 넣지 않기).
- synthetic 흐름: 흐름 소유팀과 합의 (테스트 데이터가 실데이터를 오염시키지 않게).

## 트레이드오프

| 장점 | 비용 |
|------|------|
| 사용자 관점 가용성 | 프로빙 자체가 부하 (작음) |
| 트래픽 0에서도 감지 | synthetic 데이터 관리 (오염 방지) |
| 내부 계측 오류에 독립 | 원인은 모름 — "안 된다"만 앎 |
| 흐름 전체 검증 (synthetic) | ready 검사가 무거우면 연쇄 장애 위험 |

## 못 잡는 것 → 보완
원인 분석 → SY-01 + FL-04 / 개별 비즈니스 실패 → SG 계열 / 프로메테우스 자신 → SY-03

## 실험 가설
→ [실험 계획 · SY-02](../../04-experiments/experiment-plan.md#sy-02)
- H1: Toxiproxy로 DB 연결만 차단하면 SY-01 규칙은 트래픽이 있을 때만 발화하고, NotReady는 **부하 0에서도** 발화한다.
- H2: FL-02 모드에서 shipping consumer만 pause하면 health는 정상인데 SyntheticFail이 발화한다.
- H3: 서비스 kill 시 SY-01 ServiceDown과 SY-02 Unreachable이 중복 발화 → 억제/그룹 규칙으로 1건 수렴.
