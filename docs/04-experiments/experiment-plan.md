# 실험 계획

> 방법론별로 **정상 동작(유실·중복·감지)**과 **부하 특성(지연·처리량·리소스)**을 같은 조건에서 비교한다.
> 도구: k6(부하 + 장애 주입 헤더), Toxiproxy · docker(인프라 장애), mock-slack · Mailpit(수신 sink), analyzer(정답 대조).

관련: [인프라 구조](../02-infrastructure/infra-architecture.md) · [방법론 지도](../03-methodologies/README.md)

---

## 1. 연구 질문

| RQ | 질문 | 주 지표 |
|----|------|--------|
| RQ1 | 실패가 **유실 없이** 알림에 도달하는가 | 커버리지, 유실률 |
| RQ2 | 사람이 보는 알림이 **문제 수에 비례**하는가 (소음) | 알림 수, 중복 수 |
| RQ3 | 알림 메커니즘이 **본 흐름 성능**을 해치는가 | 주문 p95/p99, 처리량, 에러율 |
| RQ4 | 실패 발생부터 알림 도달까지 **얼마나 걸리는가** | 종단 지연, 감지 시간 |
| RQ5 | 장애 후 **스스로 복구**하는가, 얼마나 걸리는가 | 적체 소진 시간, 복구 후 유실 |
| RQ6 | 생산자에게 **얼마나 결합**되는가 | 알림 관련 LOC, 요청당 추가 DB 쓰기 |
| RQ7 | 부재·시스템·메타 실패를 **감지하는가** | 감지 여부, 감지 시간, 오탐률 |

---

## 2. 공통 환경

- 같은 호스트, 같은 compose 리소스 제한(서비스 CPU 1·메모리 512MB 등 — 구현 시 확정), 같은 데이터 초기 상태.
- 실행마다: 초기화 → 워밍업 2분(측정 제외) → 본 측정 → 정리(cooldown 2분, 적체 소진 관찰).
- 각 조합 **3회 반복**, 중앙값 보고 + 편차 기록.
- k6는 **open model**(`constant-arrival-rate`)로 실행한다. 서버가 느려져도 부하가 줄지 않게 해서 coordinated omission을 피한다.
- 단일 호스트이므로 **절대 수치보다 방법론 간 상대 비교**에 의미를 둔다.

---

## 3. 정답(ground truth) 측정 방식

ID 없는 아키텍처를 검증하려면 **관측용 ID**가 필요하다. 이것은 아키텍처의 일부가 아니라 테스트 하네스의 일부다.

```
 k6 ─ 요청 헤더: X-Test-Run-Id, X-Request-Id, X-Fault ─▶ 서비스
        │                                               │ 실패 이벤트에 관측 속성으로 첨부
        │ expected.jsonl                                 │ (dedup·라우팅 로직은 사용 금지)
        │ {requestId, fault, injectedAt, expectedCategory}▼
        │                                  notification → mock-slack / Mailpit
        │                                               │ received.jsonl
        ▼                                               ▼ {receivedAt, fingerprint, count, samples, requestIds?}
 ┌──────────────────────────── analyzer ────────────────────────────┐
 │ 개별형(SG-01/02/03): requestId 1:1 대조 → 유실·중복              │
 │ 묶음형(SG-04 등):    fault 종류별 "어느 알림에든 포함되었나" +     │
 │                      알림 count 합 = 주입 건수 검증              │
 │ 감지형(FL-02/SY-*):  주입 이벤트 시각 → 첫 관련 알림 시각          │
 │ 오탐:                주입하지 않은 실패에 대한 알림               │
 └──────────────────────────────────────────────────────────────────┘
```

- **analyzer 자체 검증(Phase 0)**: 알려진 개수의 실패를 주입하고 analyzer가 정확히 그 개수를 보고하는지 먼저 확인한다. 측정 도구를 검증하지 않으면 모든 결과가 무의미하다.
- 장애 주입 시각은 k6와 주입 스크립트가 `events.jsonl`에 기록한다(docker kill, toxiproxy 전환 등).

---

## 4. 측정 지표

| 지표 | 정의 | 출처 |
|------|------|------|
| `flow_throughput` | 초당 완료 주문 | k6 |
| `flow_p95`, `flow_p99` | 주문 API 응답 지연 | k6 |
| `flow_error_rate` | 의도하지 않은 5xx 비율 (주입분 제외) | k6 + analyzer |
| `alert_coverage` | 주입한 실패 중 알림에 반영된 비율 | analyzer |
| `alert_loss` | 1 − coverage | analyzer |
| `alert_dup` | 같은 실패(또는 같은 fingerprint·윈도우)가 정책 이상으로 반복 발송된 횟수 | analyzer |
| `alert_volume` | sink가 받은 알림 메시지 수 | sink |
| `alert_e2e_p50/p95` | 실패 주입 시각 → sink 수신 시각 | analyzer |
| `detection_time` | 부재·시스템·메타 장애 주입 → 첫 관련 알림 | analyzer |
| `false_positive` | 주입 없는 실패 알림 수 | analyzer |
| `recovery_drain` | 장애 해소 → 적체(outbox/lag/inbox) 0까지 시간 | Prometheus |
| `producer_db_writes` | 주문 1건당 생산자 DB 쓰기 수 | postgres 통계 |
| `resource` | 컨테이너별 CPU·메모리 평균/최대 | cAdvisor |
| `alert_loc` | 생산자 서비스의 알림 관련 코드 라인 | 정적 집계 |

---

## 5. 장애 주입 카탈로그

| ID | 주입 | 방법 | 대상 실패 |
|----|------|------|----------|
| FI-01 | 비즈니스 실패 | `X-Fault: biz` | F1 |
| FI-02 | 기술 예외 | `X-Fault: tech` | F2 |
| FI-03 | 처리 지연 | `X-Fault: latency=<ms>` (서비스 지정 가능) | F3 / 성능 |
| FI-04 | 하위 타임아웃 | shipping `latency` > 오케스트레이터 timeout | F3 (결과 불명) |
| FI-05 | 소비자 정지 | `docker pause shipping` (코레오그래피) | F4 |
| FI-06 | 보상 실패 | product `X-Fault: compensation-fail` | F5 |
| FI-07 | 커밋 후 크래시 | `X-Fault: crash-after-commit` | P4 (dual write) |
| FI-08 | 알림 서비스 다운 | `docker stop notification` N초 | F7 / RQ5 |
| FI-09 | 채널 5xx / 429 | mock-slack 모드 전환 | F7 (채널) |
| FI-10 | 채널 지연 | mock-slack `latency=3000` | F7 / 성능 |
| FI-11 | 브로커 다운 | `docker stop kafka` N초 | F6 |
| FI-12 | DB 지연·단절 | Toxiproxy `latency` / `timeout` | F2, F6 |
| FI-13 | 서비스 kill | `docker kill shipping` | F6 |
| FI-14 | 중복 전달 | relay 이중 발행 모드 / 202 응답 유실 (Toxiproxy) | 중복 처리 |
| FI-15 | 모니터링 스택 다운 | `docker stop prometheus` / `alertmanager` | F7 (메타) |
| FI-16 | 수집 경로 다운 | `docker stop alloy` | SG-07 유실 |

---

## 6. k6 시나리오

| ID | 이름 | 부하 | 장애 | 목적 |
|----|------|------|------|------|
| K1 | 기준 | 50 → 100 → 200 rps 단계 (각 3분) | 없음 | 방법론별 순수 오버헤드 (RQ3, RQ6) |
| K2 | 혼합 정상상태 | 100 rps 10분 | FI-01 10% · FI-02 5% · FI-03 5% | 일상 운영 조건 (RQ1, RQ2, RQ4) |
| K3 | 실패 폭주 | 100 rps, t=2m~3m에 FI-02 50% | 폭주 구간 | 알림 피로 (RQ2) |
| K4 | 혼돈 타임라인 | 100 rps 15분 | t=3m FI-08(30s) · t=6m FI-09(60s) · t=9m FI-13 · t=12m FI-11(30s) | 복합 장애·복구 (RQ1, RQ5, RQ7) |
| K5 | 소크 | 50 rps 30분 | FI-01 5% | 적체·메모리 누수·보관 정리 |
| K6 | 표적 주입 | 방법론별 지정 | 방법론별 FI | 각 방법론 가설 검증 |

---

## 7. 방법론별 실험 계획

<a id="sg-01"></a>
### SG-01 · messageId 왕복 핸드셰이크 (기준선)
- **시나리오**: K1, K2, K3, K6(FI-07, FI-09, FI-14)
- **가설 검증**
  - H1 FI-07 → `alert_loss` = 주입 건수, SG-01 내부 지표(미확인 건)에는 나타나지 않음
  - H2 FI-09 60s → 서비스로 유입되는 재시도 요청 수 시계열이 증가 추세
  - H3 K1 → `producer_db_writes`, `flow_p95`가 SG-02보다 높음
- **변형**: SG-01+ (SY-01 부착) — 운영 경로 유무 효과 분리

<a id="sg-02"></a>
### SG-02 · Outbox + 멱등 Inbox
- **시나리오**: K1, K2, K3, K4, K6(FI-07, FI-08, FI-14)
- **가설 검증**
  - H1 FI-07·FI-08·FI-14 → `alert_loss` 0, 중복 수신 0
  - H2 FI-08 30s → `flow_p95` 변화 없음, `recovery_drain` 측정
  - H3 relay 주기 {100ms, 500ms, 2s} × K2 → `alert_e2e_p95` vs DB CPU 곡선
  - H4 K3 → `alert_volume` = 실패 건수 (SG-04 비교 기준)

<a id="sg-03"></a>
### SG-03 · 브로커 at-least-once
- **시나리오**: K1, K2, K4, K6(FI-08, FI-09, FI-11, 소비자 kill)
- **가설 검증**
  - H1 FI-08 / 소비자 kill → loss 0, dup ≤ kill 횟수 × 배치 크기
  - H2 FI-09 60s, 재시도 토픽 분리 vs 미분리 → 정상 메시지 `alert_e2e_p95`
  - H3 K1 → 알림 서비스 DB 부하 SG-02 대비 감소

<a id="sg-04"></a>
### SG-04 · Fingerprint 묶음 처리
- **시나리오**: K2, K3, K4 (SG-02 또는 SG-03 위에서)
- **가설 검증**
  - H1 K3 → `alert_volume` SG-02 대비 ≥10배 감소, 주입한 fault 종류 커버리지 100%, count 합 = 주입 건수
  - H2 critical 첫 건 지연 = SG-02 수준, warn 지연 ≈ 윈도우
  - H3 집계 메모리 전용 vs DB 저장, 윈도우 중 크래시 → 손실 여부
- **변형**: 윈도우 {10s, 60s, 300s}

<a id="sg-05"></a>
### SG-05 · CDC Outbox (P2)
- **시나리오**: K1, K2, K6(커넥터 정지 5분)
- **가설 검증**: H1 SG-03 대비 발행 지연·DB CPU 감소 / H2 slot lag 선형 증가 + 감지 / H3 메모리 사용량 증가폭

<a id="sg-06"></a>
### SG-06 · Pull 폴링 (P2)
- **시나리오**: K1, K2, K6(동시 쓰기 고부하)
- **가설 검증**: H1 safety_lag 유/무 → 건너뛴 실패 수 / H2 `alert_e2e_p95` ≈ 주기/2 + safety_lag / H3 실패 0 구간 폴링 부하

<a id="sg-07"></a>
### SG-07 · 로그 기반 (P2)
- **시나리오**: K2, K6(FI-16)
- **가설 검증**: H1 정상 시 커버리지 ≈ SG-02, FI-16 구간 유실 발생 / H2 `alert_loc` 최소 / H3 `alert_e2e_p95` 큼

<a id="fl-01"></a>
### FL-01 · 동기 오케스트레이션
- **통신 모드**: A (동기 REST)
- **시나리오**: K1, K2, K6(FI-03 shipping, FI-04, FI-06, FI-13 shipping)
- **가설 검증**
  - H1 FI-03 shipping 500ms → `flow_p95` +500ms (FL-02와 대비)
  - H2 FI-04, 멱등 + 상태조회 유/무 → 재고 불일치 건수
  - H3 FI-06 → F5 critical 커버리지 100%
  - H4 FI-13 → FlowFailed 폭주 vs 원인 1건 수렴 (SY-02 + SG-04 결합)

<a id="fl-02"></a>
### FL-02 · 코레오그래피 + 워치독
- **통신 모드**: B (이벤트)
- **시나리오**: K1, K2, K4, K6(FI-03 shipping, FI-05, FI-06, 워치독 재시작)
- **가설 검증**
  - H1 FI-05 → `detection_time` ≤ 기한 + 스케줄러 주기, SG 단독은 알림 0
  - H2 FI-03 → `flow_p95` 변화 없음, 흐름 완료 지연 증가
  - H3 기한 {p99×1.5, ×3, ×10} → `false_positive` vs `detection_time` 곡선
  - H4 워치독 재시작, lag 보정 유/무 → 오탐 수

<a id="fl-03"></a>
### FL-03 · Process Manager (P2)
- **통신 모드**: C (명령 + 이벤트)
- **시나리오**: K1, K2, K6(product 짧은 pause, FI-06)
- **가설 검증**: H1 짧은 pause → 알림 없이 자동 복구 / H2 F5 커버리지 100% / H3 처리량 FL-02 대비

<a id="fl-04"></a>
### FL-04 · trace 상관
- **시나리오**: K1 (계측 on/off), K2
- **가설 검증**: H1 계측 오버헤드(`flow_p95` 차이) / H2 알림의 traceId 샘플 Jaeger 조회 성공률 100% / H3 비동기 구간 trace 연속성

<a id="fl-05"></a>
### FL-05 · Reconciliation (P2)
- **시나리오**: K2 + 워치독 정지 구간, K6(FI-16)
- **가설 검증**: H1 워치독 정지 중 정지 흐름 → 다음 주기 내 전부 발견 / H2 파이프라인 유실 정량 검출 / H3 settle_time 짧을 때 오탐

<a id="sy-01"></a>
### SY-01 · Level-triggered 메트릭
- **시나리오**: K2, K4, K6(FI-12, FI-13, notification pause)
- **가설 검증**
  - H1 FI-13 → `detection_time` ≤ scrape + for + group_wait
  - H2 webhook 대상 pause 중 장애 지속 → 복구 후 도달
  - H3 FI-12 DB 단절, inhibit 유/무 → `alert_volume`
  - H4 변형 A/B × (notification + shipping 동시 다운) → F6 도달 여부

<a id="sy-02"></a>
### SY-02 · Blackbox · Health 프로빙
- **시나리오**: K6(부하 0 + FI-12, 모드 B + FI-05, FI-13)
- **가설 검증**: H1 부하 0에서 NotReady 발화 (SY-01 에러율은 미발화) / H2 consumer pause → health 정상 + SyntheticFail / H3 ServiceDown·Unreachable 중복 → 1건 수렴

<a id="sy-03"></a>
### SY-03 · Dead man's switch
- **시나리오**: K6(FI-15 prometheus, FI-15 alertmanager, FI-08)
- **가설 검증**: H1 각 중지 → 임계 + 1분 이내 Mailpit 경보 (변형 1/2 범위 차이 확인) / H2 SY-03 없는 구성 → 알림 0 (조용한 실패 재현)

<a id="in-01"></a>
### IN-01 · 통합 구조
- **시나리오**: K2, K3, K4, K5
- **검증**: F1~F7 전 유형 커버리지 100%, K4 혼돈 타임라인에서 각 장애가 **원인 알림 1건 + 해소 알림**으로 수렴, 본 흐름 p95가 K1 기준 대비 허용 범위 내

---

## 8. 실행 순서

| Phase | 내용 | 산출물 |
|-------|------|--------|
| 0 | 인프라 기동 · 장애 주입 훅 · sink · **analyzer 자체 검증** | 하네스 검증 보고 |
| 1 | 기준선 SG-01 (+ SG-01+) | 기준 수치 |
| 2 | 단일 P1: SG-02 → SG-03 → SG-04 | 슬롯 S 후보 결정 |
| 3 | 흐름 P1: FL-01 vs FL-02 (+ FL-04 상시) | 슬롯 F 후보 결정 |
| 4 | 시스템 P1: SY-01 (A/B) · SY-02 · SY-03 (1/2) | 슬롯 O·M 변형 결정 |
| 5 | 통합 IN-01: 선정 조합으로 K2~K5 | 최종 구조 근거 |
| 6 | P2 (여력 시): SG-05 · SG-06 · SG-07 · FL-03 · FL-05 | 조건부 대안 근거 |

---

## 9. 타당성 위협

| 위협 | 영향 | 완화 |
|------|------|------|
| 단일 호스트 자원 경합 | 절대 수치 왜곡 | 리소스 제한 고정, 상대 비교, k6 CPU 별도 관찰 |
| JVM 등 런타임 워밍업 | 초반 지연 왜곡 | 워밍업 2분 제외 |
| mock sink가 실제 Slack보다 빠름 | 알림 지연 과소평가 | FI-10으로 채널 지연 별도 측정 |
| 컨테이너 재시작 시간 | 복구 시간 왜곡 | 재시작 시각을 events.jsonl로 기록해 보정 |
| 감시자가 같은 호스트 | SY-03 실패 영역 분리 불완전 | 한계로 명시 |
| 3회 반복의 통계적 한계 | 작은 차이는 노이즈 | 편차 기록, 차이가 편차보다 클 때만 결론 |

---

## 10. 결과 기록 형식

결과는 `docs/04-experiments/results/<날짜>-<방법론ID>-<시나리오>.md`에 남긴다.

```
# <방법론ID> × <시나리오> — <날짜>
- 구성: 프로필, 변형, 파라미터 (윈도우·기한·relay 주기 등)
- 커밋 SHA
- 가설별 결과: H1 ✔/✘ (수치) …
- 지표 표 (3회 중앙값 ± 편차)
- 관찰된 예상 밖 동작
- 우선순위 변경 제안과 근거
```
