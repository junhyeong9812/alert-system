# 요구사항 명세서 (requirement-spec)

> 작성일: 2026-11-03 · 작업 폴더: `docs/plans/2026-11-03/phase0-1-harness-baseline/`

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "진행하자." — 직전 합의: 설계 문서(README, docs/01~04) 작성 완료 후 "실제 서비스·인프라 코드로 넘어가기 전 명세 인터뷰(언어·스택, 브로커, 1차 범위, 리소스)부터" 진행.
- 호스트 실측 (질문 대신 확인): 24코어 · 가용 메모리 약 14GB · **디스크 여유 27GB(95% 사용)** · Docker 29.1 / Compose 2.40 · Java 21 · Go 1.27 · Python 3.12 · k6 2.3 · Gradle/Kotlin 설치됨 · Spring Boot 최신 안정판 4.1.2 (start.spring.io 확인)
- Q/A:
  | 질문 | 답 |
  |------|-----|
  | 서비스 언어/프레임워크 | **Spring Boot + Kotlin** |
  | 브로커 | **Kafka (KRaft, apache/kafka)** — 단 Phase 0~1에는 불필요 (아래 통신 모드 답변) |
  | 1차 구현 범위 | **Phase 0~1** (인프라 + 서비스 골격 + 장애 주입 + sink + analyzer 자체 검증 + 기준선 SG-01 + 실험) |
  | git 흐름 | **feature 브랜치 + PR** |
  | 빌드 구조 | **서비스별 독립 프로젝트** (공용 코드는 서비스마다 복사) |
  | SG-01+ 변형 | **포함, Grafana도 포함** (Prometheus + Alertmanager + Grafana) |
  | stakes | **중간** |
  | 금지영역 | **설계 문서는 별도 커밋** — 코드 커밋에 docs/01~04를 섞지 않음, 불일치는 보고 → 승인 → 별도 docs 커밋 |
  | 보조 도구 언어 | **Go** (mock-slack, analyzer) |
  | Phase 0~1 통신 모드 | **동기 REST만** (모드 A) — Kafka는 Phase 2에서, 미리 만들어두지 않음 |
  | SG-01 충실도 | **원안 전체** (msgId · alert_status · ack · 배치 카운트 · 재시도 명령 · 재전송 API) |

---

## 1. 목표·대상 (필수)

`/home/jun/project/alert-system` 저장소에서 **Phase 0**(docker compose 인프라 + 4개 Spring Boot/Kotlin 서비스의 동기 REST 흐름 + 장애 주입 훅 + mock-slack(Go)·Mailpit sink + k6 시나리오 + analyzer(Go) 자체 검증)과 **Phase 1**(기준선 SG-01 원안 전체 구현 + SG-01+ 변형(Prometheus·Alertmanager·Grafana) + 실험 계획 §7 SG-01 시나리오 실행·결과 문서화)이 끝나고, feature 브랜치 PR로 올라가면 완료.

## 2. 경계·불변식 (필수)

- **I1 관측 id 분리**: `X-Test-Run-Id`·`X-Request-Id`는 실패 이벤트에 관측 속성으로만 실린다. 아키텍처 로직(dedup·라우팅·재시도·ack)은 이 값을 읽지 않는다.
- **I2 장애 주입 격리**: 장애 주입 훅은 `FAULT_INJECTION_ENABLED=true`일 때만 동작한다. false면 `X-Fault` 헤더를 무시한다.
- **I3 측정 도구 선검증**: analyzer는 자체 검증(합성 데이터셋 단위 테스트 + 실 파이프라인 known-N 주입에서 정확히 N 보고 · 인위 유실/중복을 정확히 검출)을 통과하기 전에는 실험 결과 산출에 쓰지 않는다.
- **I4 기준선은 결함을 재현한다**: SG-01은 원안대로 구현하고 문서의 결함(L1 ack 유실 중복 · L4 재시도 증폭 · L6 dual write 유실)을 **고치지 않는다.** 결함 재현이 기준선의 목적이다.
- **I5 서비스 독립**: 각 서비스는 독립 Gradle 프로젝트이고, 서비스 간 코드 의존이 없다(공용 코드는 복사). 서비스 간 통신은 HTTP만 쓴다.
- **I6 DB 소유**: Postgres 인스턴스 1개에 DB 4개를 두고, 서비스는 자기 DB에만 접근한다.
- **I7 sink 무손실**: mock-slack은 수신한 요청을 장애 모드와 무관하게 **모두** 기록한다(장애 모드로 거절한 요청도 `rejected`로 기록). sink가 유실하면 측정이 무효가 된다.
- **I8 부하 모델**: k6는 open model(`constant-arrival-rate`)로 돌린다.
- **I9 디스크 예산**: 새 이미지·볼륨 총량은 약 6GB 이내로 하고, 빌드는 멀티스테이지·JRE 런타임 이미지로 한다.

## 3. 기준소스 (필수)

1. 사용자 인터뷰 답변 (§0) — 최우선
2. `docs/04-experiments/experiment-plan.md` — §3 정답 측정 방식, §5 장애 주입 카탈로그, §6 k6 시나리오, §7 SG-01, §8 Phase 0
3. `docs/03-methodologies/single/sg-01-id-ack-handshake.md` + `docs/01-problem/01-initial-design-review.md` §1·§5 — SG-01 원안 동작과 재현할 루프
4. `docs/02-infrastructure/infra-architecture.md` — 구성 요소, 포트, 장애 주입 지점, 모드 A
5. `docs/01-problem/03-ownership-and-routing.md` §5 — Notifier 추상화(Slack/Email)
6. 버전: Spring Boot 4.1.2 (start.spring.io 기본값), Kotlin은 같은 메타데이터 기본값, Java 21

## 4. 금지영역 (필수)

- 코드 커밋에 `docs/01~04`·`README.md` 설계 내용을 섞지 않는다. 불일치를 발견하면 보고 → 승인 → 별도 docs 커밋으로 처리한다(실험 결과 문서·이 작업 폴더도 docs 커밋으로 분리).
- `main`에 직접 커밋하지 않는다. feature 브랜치에서 작업하고 PR로 병합한다. push·PR 생성·원격 브랜치 생성은 **각각** 사용자 확인 후 진행한다.
- 호스트의 기존 Docker 리소스(`ccle-postgres-seed`, `postgres:16/17` 등 다른 프로젝트 이미지·컨테이너·볼륨)는 삭제하거나 prune하지 않는다. 정리는 이 프로젝트 compose(project name `alert-system`)의 리소스로 한정한다.
- 이번 범위 밖은 만들지 않는다: Kafka, SG-02 이후 방법론, 코레오그래피 모드, Loki, OTel·Jaeger, Debezium, Blackbox, dead-man-switch.
- 커밋·PR 본문에 AI attribution과 리뷰 출처를 넣지 않는다.

## 5. 검증 방법 (필수)

| 대상 | 방법 | 통과 기준 |
|------|------|----------|
| 서비스 단위 | 서비스마다 `./gradlew test` | green. 장애 주입 훅 on/off, SG-01 상태 전이(SENT→ACKED/FAILED), 배치 재시도 명령 임계 로직 포함 |
| 보조 도구 단위 | `go test ./...` (mock-slack, analyzer) | green. analyzer는 합성 데이터셋(정상·유실·중복·묶음 없음)으로 정확도 검증 |
| 기동 smoke | `docker compose up` → 4개 서비스 `/health/ready` | 전부 200 |
| E2E smoke | 정상 주문 1건, `X-Fault: biz`·`tech` 각 1건 | 정상 = CONFIRMED / 실패 건이 mock-slack 기록에 도달 |
| analyzer 실 파이프라인 검증 (I3) | known-fault N=100 주입 (장애 없는 채널) | coverage 100%, dup 0을 정확히 보고 |
| SG-01 결함 재현 smoke | FI-07 crash-after-commit / FI-09 Slack 5xx | FI-07: 유실이 관측됨 / FI-09: 재시도 명령 트래픽 증가가 관측됨 |
| SG-01+ smoke | shipping kill | Alertmanager → 채널에 ServiceDown 도달 |
| Phase 1 실험 | 실험 계획 §7 SG-01 시나리오 (K1·K2·K3·K6), 3회 반복 | 결과 문서 `docs/04-experiments/results/`에 가설별 판정·수치 기록 |
| 리뷰 | 중간 stakes — 듀얼 1패스 (Opus 워커 ∥ codex → 종합 → post-fix 재점검 1회) | ledger 기록 |
| 머지 전 안전선 | core §4 ①~⑥ | log 기록 |

## 6. stakes (필수)

- 판정: **중간** — 근거: 동시성·재시도·스케줄러(배치)·외부 sink가 핵심이고, 측정 하네스가 틀리면 실험 결론이 무효가 된다. 로컬 실험이라 불가역 데이터·보안·운영 영향은 없다. 사용자 지정(중간)과 표 판정이 일치한다.

---

## 7. 자율성 (모드 — stakes와 별개 축)

- [x] auto (기본 권장 — 합의 후 Claude 자율 실행)
- [ ] lazy (매 diff 이해 게이트 — 학습·OSS 기여)

## 8. load-bearing 가정 (1~2개)

- **A1 리소스**: JVM 서비스 4개(각 메모리 제한 512MB) + Postgres + Prometheus·Alertmanager·Grafana + Mailpit + Toxiproxy + mock-slack이 가용 메모리 14GB·디스크 27GB 안에서 k6 200 rps 부하 중에도 안정적으로 동작한다. → task 01 직후 골격 기동 + 짧은 부하로 실증한다.
- **A2 크래시 정답 집계**: `crash-after-commit`으로 프로세스가 종료돼도 compose `restart` 정책으로 서비스가 자동 복귀하고, k6가 연결 실패 응답에서도 해당 요청을 expected에 기록해 analyzer가 유실을 정확히 셀 수 있다. → task 05에서 실증한다.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 저장소 골격 + compose(core: postgres, mailpit, toxiproxy) + 4개 서비스 Spring 골격(health/metrics, 독립 Gradle, 멀티스테이지 Dockerfile) | — | compose up 후 4개 ready + **A1 smoke** |
| 02 | 동기 REST 흐름(order 오케스트레이터 → product 차감 → shipping 생성, 실패 시 보상) + 장애 주입 훅(biz/tech/latency/crash-after-commit/compensation-fail) | 01 | 단위 테스트 green, 정상·실패 E2E smoke |
| 03 | mock-slack (Go): 수신 기록(I7), 장애 모드 API(5xx/429/latency/blackhole), 기록 조회 API | 01 | go test green, compose 기동 |
| 04 | SG-01 원안: 서비스 쪽 msgId·alert_status·ack 수신·재전송 API / notification 쪽 inbox·Notifier(Slack/Email)·ack 회신·실패 배치 카운트·재시도 명령 | 02, 03 | 상태 전이·배치 테스트 green, 실패 → mock-slack 도달 |
| 05 | k6 시나리오(K1/K2/K3/K6 + expected.jsonl·events.jsonl) + analyzer (Go) + 자체 검증 → **Phase 0 완료** | 03, 04 | analyzer 합성 + 실 파이프라인 N=100 검증 통과(I3), **A2 실증** |
| 06 | SG-01+: Prometheus·Alertmanager·Grafana + SY-01 규칙 서브셋(ServiceDown, HighErrorRate) → 채널 직접(변형 A) | 04 | shipping kill → ServiceDown 도달 |
| 07 | SG-01 결함 재현 smoke + Phase 1 실험 3회 실행 + 결과 문서(docs 커밋 분리) | 05, 06 | 가설 H1~H3 판정 기록 |
| 08 | 듀얼 리뷰 1패스 + 머지 전 안전선 + PR | 07 | ledger, PR 생성(확인 후) |

---

## 승인 상태

- [x] 필수 6칸 전부 기입 (빈 칸 없음)
- [x] 사용자 합의 → SPEC=1 (2026-11-03 set-state 기록)
- [x] 자율성 선택 → MODE=auto 기록
