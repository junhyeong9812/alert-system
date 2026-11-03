# log — phase0-1-harness-baseline

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 2026-11-03 | 호스트 실측 | 24코어 · 가용 메모리 14GB · 디스크 여유 27GB(95%) · Spring Boot 4.1.2 최신 안정판 확인 |
| 2026-11-03 | 인터뷰 3라운드 완료 | Kotlin/Spring · Kafka(Phase 2부터) · Phase 0~1 · feature+PR · 독립 프로젝트 · SG-01+ Grafana 포함 · stakes 중간 · docs 별도 커밋 · Go 보조 도구 · 동기 REST만 · SG-01 원안 전체 |
| 2026-11-03 | requirement-spec 작성 | 사용자 합의 대기 |
| 2026-11-03 | 사용자 합의 → SPEC=1, MODE=auto · 브랜치 feat/phase0-1-harness-baseline | 명세 docs 커밋 1208e85 |
| 2026-11-03 | task 01~04(서비스 측) start.spring.io 골격 생성 | Spring Boot 4.1.2 · Kotlin 2.3.21 · Gradle 9.8.2 · Jackson 3 |
| 2026-11-03 | 결정: 테스트 순서 — 명세에서 T-O1~5/T-P1~5/T-R1~3/T-N1~7 설계 후 구현 | Testcontainers(postgres:17 기존 이미지 재사용) |
| 2026-11-03 | 결정: SG-01 생산자는 ack 타임아웃 재전송을 하지 않음 | 원안 해석 — 재시도는 알림 서비스의 명령으로만 (I4). 미확인 SENT는 alert_status_count로 관측 |
| 2026-11-03 | 결정: 재시도 명령 전에 RETRY_REQUESTED로 먼저 전이 | 생산자 동기 재전송이 명령 응답보다 먼저 도착하는 경합에서 메시지가 고착되는 것을 방지 (명령 실패 시 FAILED 복귀) |
| 2026-11-03 | 컴파일 오류 3건 (JdbcClient params nullable, SimpleMailMessage apply 섀도잉) | 수정 |
| 2026-11-03 | 서비스 단위 테스트 | order 10 · product 4 · shipping 6 · notification 8 = 28 green |
| 2026-11-03 | mock-slack(Go) | go test -race 4 green (동시 500건 무손실 · 거절 요청 기록 포함) |
| 2026-11-03 | compose core 기동 + E2E smoke | 4개 ready · 정상 201 · biz/tech/보상실패 알림 mock-slack 도달 · critical 메일 1건 · toxiproxy 경유 ack 5건 ACKED |
| 2026-11-03 | **A1 1차 반증**: 512m/2cpu, 200 rps 60s | 콜드: p50 2.56s·누락 66/s / 워밍 후: p95 1.15s, order CPU 212%(한계)·메모리 475/512 |
| 2026-11-03 | 조치: JDK HttpClient HTTP/1.1 고정(h2c 업그레이드 제거) + order cpus 4 | 워밍 후 200 rps 누락 0, p95 61ms · 단 product 메모리 511/512 |
| 2026-11-03 | 조치: Spring 서비스 mem_limit 768m (A1 수정 — 명세 가정 '512MB' 반증) | 합계 ~3GB, 가용 10GB. 실험은 워밍업 구간을 측정에서 제외(실험 계획 §2) |
| 2026-11-03 | k6 시나리오 K0·K1·K2·K3·K6(fi07/fi09/fi14) + warmup · harness(정답 로그: console.log → --log-output raw) | k6 raw 로그·randomUUID·연결 실패 시에도 정답 행 기록 확인 |
| 2026-11-03 | analyzer(Go) 합성 테스트 + 변이 검사 2건 | 9 green · 변이(거절 집계 제거, 중복 무시) 모두 테스트가 검출 |
| 2026-11-03 | **I3 실 파이프라인 검증** K0 (known-fault 100건) | 정답 100 · 도달 100 · 중복 0 · DB inbox SENT 100과 일치 → analyzer 실험 사용 허용 |
| 2026-11-03 | FI-07 1차: 유실 84 보고 — **측정 모델 결함 발견** | 다운된 서비스에 도달 못 한 주입까지 정답으로 셈. 정답 = 실패가 실제 발생한 요청(응답의 자기 오류 코드로 판정, crash는 주입 그대로)으로 수정, 미도달은 별도 집계 |
| 2026-11-03 | 독립 교차 측정 추가: 서비스별 실패 기록 수 − alert_status 수 (dual write 공백) | run-experiment.sh db-state |
| 2026-11-03 | **A2 실증** FI-07 재실행 | shipping 크래시 5회 자동 복귀 · analyzer 유실 5 = crash 주입 5 · DB 공백 109−104=5 일치 → L6 재현 |
| 2026-11-03 | task 06 obs 기동 | alertmanager 최상위 x-slack 앵커 거부 → receiver별로 풀어 씀 |
| 2026-11-03 | SG-01+ smoke 1차: ServiceDown 46.8s 도달, 단 RESOLVED 누락·1분마다 FIRING 재전송·예상 밖 15 | 원인 ① **mock-slack 충실도 버그**: 200 본문 "OK" ≠ Slack "ok" → Alertmanager가 실패 처리 / ② 재기동 직후 order 타임아웃(2s) 뒤에 shipping이 처리 완료 = FL-01 결과 불명 현상 |
| 2026-11-03 | 수정: mock-slack 본문 ok + 테스트 · order 실패 사유에 근본 예외 · 하네스 결과 불명 분류(유실·예상 밖과 분리) | 재실행: FIRING→RESOLVED 정상, 예상 밖 0 |
| 2026-11-03 | 컨테이너 중지 시 근본 예외 = UnresolvedAddressException(9176건) → 미도달로 분류. ClosedChannel·EOF·Timeout은 보수적으로 불명 | |
| 2026-11-03 | 결정: 듀얼 리뷰(task 08)를 Phase 1 실험(task 07)보다 먼저 | 리뷰 수정이 3시간 실험을 무효화하지 않도록. 리뷰 중 FI-09·FI-14·SG-01+ smoke 병행 |
| 2026-11-03 | 리뷰 1패스 종합 + codex 감사 → R1~R13 수정 | 서비스 테스트 31 green · Go 테스트 green |
| 2026-11-03 | smoke(수정 전 코드): FI-14 inbox SENT 693 · 수신 1386(dup-send) · 중복 0 · ack 콜백 실패 241 | 수정 전이라 L1 미재현 — 리뷰 R3 지적과 일치 |
| 2026-11-03 | FI-09 smoke 중단 | 실행 중 run-experiment.sh 편집 → bash 증분 읽기 문법 오류(측정 결함 아님). 이후 실행은 편집 금지 |
| 2026-11-03 | 멀티스테이지 재빌드 1차 실패 → 성공 | 4개 병렬 빌드의 gradle 캐시 마운트 경합 추정 → sharing=locked. 이미지 321MB/서비스, 디스크 여유 21GB |
| 2026-11-03 | post-fix 재점검: codex 2회 실패(bwrap 샌드박스 초기화 오류로 파일 읽기 불가) | playbook ① 실패 분기 → 같은 packet으로 대체 독립 리뷰어(새 Opus 워커) 실행 |
| 2026-11-03 | 수정 후 smoke 5종 | K0 100/100 · FI-07 유실 5=크래시 5, 미도달 4 제외, DB 공백 5 · FI-09 거절 556→재시도 명령 556→유실 0, e2e p95 72.7s · FI-14 재발송 708(L1 재현)·중복 0(inbox 선기록 dedup) · SG-01+ kill ServiceDown 43.3s·RESOLVED·정지 유지(R10 확인) |
| 2026-11-03 | 보정: 기대 패턴 없는 장애 이벤트는 감지 측정 제외 · 카운터 실행별 증분 | FI-14 '14s 감지'는 주입 tech 실패의 HighErrorRate였음(무관) |
| 2026-11-03 | 리뷰 수정 커밋 09b2f53 · Phase 1 드라이버 실행 시작 (SG-01 ×3 반복 + SG-01+ ×3) | 예상 소요 ~3.3h |
| 2026-11-03 17:24 | Phase 1 드라이버 완료 (k6 전 실행 exit=0, `!! run failed` 0건) | SG-01 K0 + {K1,K2,K3,FI-07,FI-09,FI-14}×3 + servicedown r1 · SG-01+ servicedown ×3 |
| 2026-11-03 | 집계 `scripts/summarize-runs.py --since 20261103T140137` | K0~K3 유실·중복 0 · FI-07 유실 5=크래시 5=DB공백 5 (3/3, H1 채택) · FI-09 장애 중 시도 1.7배·복구 버킷 ~3배, e2e p95 56.4s (H2 채택·제한적) · H3 SG-02 미구현으로 이연 · FI-14 생산자 SENT 잔류 = ack 실패 수(228/234/243, L7 재현) · SG-01+ ServiceDown 46.2s, SG-01 감지 0 |
| 2026-11-03 | 결과 문서 `docs/04-experiments/results/phase1-sg01.md` + 원시 집계 `phase1-sg01-raw.md` | R8 해소 |
| 2026-11-03 | 안전선 ② 테스트 재실행(`--rerun`) | order 10·product 4·shipping 8·notification 9 전부 통과 · go test analyzer·mock-slack ok · summarize-runs.py py_compile ok |
| 2026-11-03 | 안전선 ①③④⑤⑥ | ① smoke = 수정 후 smoke 5종 + Phase 1 본 실행 ③ 09b2f53 이후 코드 diff = summarize-runs.py 신규 1파일(읽기 전용 집계) ④ 신규 저장소 기능 브랜치 — PR revert로 롤백, 데이터 마이그레이션 없음 ⑤ 외부 소비자 없는 신규 서비스·스키마, 공개 계약 영향 없음 ⑥ 반증: 유실 판정이 하네스 산물인가? → DB 공백과 독립 교차 일치(5=5), K0 100/100; 중복 0이 측정 누락인가? → FI-14 재발송 702건이 inbox received 2,156에 실재 |
| 2026-11-03 | 결과 문서 수치 정정 (완료 요약 워커 지적) | L7 bullet '236건'(smoke 값) → Phase 1 '228~243건' |
| 2026-11-03 | 사이클 마감 ①: docs/plans/NEXT.md 신규 · docs/measurement-log.md 신규 (워커 모델 opus 기록) | 완료 요약 = Opus 워커 작성, 스니펫 2건 메인 대조 일치 |
| 2026-11-03 | 아카이브 보류(spec 범위에 study-note 아카이브 없음 — 사용자 범위 확인 필요) | 후보 6건 NEXT.md 보류·이월에 등재 · 원 식별자: alert-system, dev.alertsystem.*, order/product/shipping/notification-service, mock-slack |

## 리뷰 ledger (中↑)

- review packet (loop 1, 中 듀얼 1패스): base 1208e85 / HEAD 8e1852f / $OUT=/tmp/claude-1000/-home-jun-project-alert-system/5824484e-3bba-4dfd-ba41-e32663d54207/scratchpad/review.899L / mirror=…/scratchpad/mirror.rG28 · diff 94 files +4607 (wrapper jar·gradlew 제외 목록 표기) · untracked 0 · 보안 스캔: DB 기본 비밀번호 플레이스홀더 4건 오탐 판정 통과 · 리뷰어: Opus 워커(model opus) ∥ codex(medium)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| R1 | 1 | codex F1 + opus F2 (+감사 보완) | loadtest/k6/lib/harness.js:40 | 채택 — crash 건도 연결 실패 유형으로 판정, 타임아웃은 불명 | fixed | 1 |
| R2 | 1 | codex F2 + opus OQ6 | notification Dispatch.kt:69 | 채택 — 모든 채널 시도 | fixed | 1 |
| R3 | 1 | opus F1 | order Alerting.kt:66 (+복사본) | 채택 — ack 미수신 재발송(L1, spec I4). 상한 3회는 첫 재발송으로 L1이 재현되므로 재현을 막지 않음(감사 보완 응답) | fixed | 1 |
| R4 | 1 | opus F3 + codex OQ | tools/analyzer/analyze.go:309 | 채택 — 이벤트 기대 알림 패턴 일치만 감지 | fixed | 1 |
| R5 | 1 | opus F4 | notification Dispatch.kt:101 | 채택 — 임계 초과(>)로 | fixed | 1 |
| R6 | 1 | codex F5 + opus F5 (+감사 판정 오류 지적) | services/*/Dockerfile, scripts/build.sh | 채택 — 1차 '사용자 재합의' 판정을 감사 지적으로 정정, 멀티스테이지로 명세 준수 + app.jar 고정 | fixed | 1 |
| R7 | 1 | opus F6 (+감사 보완) | tools/mock-slack/main.go:77 | 채택 — 읽기 실패도 400 + 오류 사유로 기록 | fixed | 1 |
| R8 | 1 | codex F3 (+감사) | scripts/run-experiment.sh:112 | 채택(감사 정정) — 결과 문서는 완료 조건, task 07에서 산출 → docs/04-experiments/results/phase1-sg01.md | fixed | task 07 |
| R9 | 1 | codex F4 | loadtest/k6/k1-baseline.js:11 | 채택 — constant-arrival-rate 3구간 | fixed | 1 |
| R10 | 1 | opus OQ5 (+감사 보완) | scripts/run-experiment.sh:77 | 채택 — kill 후 stop 고정, 중단 유지 실측 확인 예정 | fixed | 1 |
| R11 | 1 | 감사 (원문 opus OQ3) | order Alerting.kt:133 | 채택 — 원안 ② ack {status}: 알림 서비스가 FAILED도 회신, 생산자 SENT→ACKED/FAILED | fixed | 1 |
| R12 | 1 | 감사 (원문 opus OQ6) | notification Dispatch.kt:68 | 기각(코드) — Slack 성공·Email 실패를 전체 실패로 보고 재시도 시 Slack 중복 = 원안에 채널별 상태가 없는 기준선 결함, 결과 문서에 기록 | — | |
| R13 | 1 | 감사 (원문 opus OQ1·OQ4) | Dispatch.kt:32, analyze.go:203 | 채택 — 콜백 실패·재발송 카운터를 실행마다 수집, unknown 건수와 함께 가설 판정에 반영 | fixed | 1 |
| N1 | post-fix | opus(대체) | infra/grafana/dashboards/sg01.json:179 | 채택 — ack 패널 status별 분리 | fixed | post-fix |
| N2 | post-fix | opus(대체) | compose.yaml:3 | 채택 — 빌드 방식 주석 갱신 | fixed | post-fix |
| N3 | post-fix | opus(대체) OQ4 | tools/analyzer/analyze.go:224 | 채택 — sink 읽기 실패 기록을 sinkReadErrors로 노출 | fixed | post-fix |

- post-fix packet: base 8e1852f vs 작업트리 / mirror2.l16j / codex 재점검 2회 실패(bwrap) → 대체 Opus 워커
- 종합 감사: codex 1회 (판정 오류 3 · 누락 1 · 보완 5 → 전부 반영)
- post-fix 재점검 결과: R1~R13 해소 확인 11/11 · 정확성·동시성 신규 결함 0 · 신규 low 2(N1·N2) + OQ4(N3) 즉시 수정 — 설정·주석·보고 필드만이라 재재점검 생략, 셀프 확인(JSON 파싱·go test)
- 열린 질문(결과 문서에 기록): retry()가 ack_resend_count를 리셋하지 않음(총 재발송 상한으로 의도) · 카운터는 프로세스 재시작 시 0(FI-07 shipping 카운터 과소) ·  재시도 배치 위상(initialDelay 기동 기준)이 반복 간 편차 요인

## 생략한 검증

- (없음)

## 완료 요약

Phase 0~1을 구현했다. 구성은 다음과 같다.
- 4개 Spring Boot/Kotlin 서비스의 동기 REST 흐름과 장애 주입 훅
- SG-01 원안 전체: msgId·alert_status·ack/FAILED 회신·ack 미수신 재발송·배치 카운트·재시도 명령·재전송 API
- Go mock-slack·analyzer, k6 K0~K6 시나리오, 실험 러너·집계 스크립트
- SG-01+: Prometheus·Alertmanager·Grafana, ServiceDown·HighErrorRate 규칙

Phase 1 결과(각 시나리오 3회, 중앙값):
- K0~K3: 유실 0, 중복 0.
- **H1 채택**: FI-07에서 크래시 5회 = 유실 5 = DB 공백 5가 3/3 반복 모두 일치했다. 내부 '미확인' 지표에는 나타나지 않았다.
- **H2 채택(제한적)**: 장애 60초 동안 채널 시도가 도착분의 1.7배였다. 복구 직후 30초 버킷은 평상시의 약 3배였고, 영향 건의 e2e p95는 56.4s였다.
- **H3 이연**: 비교 대상인 SG-02가 아직 없다.
- **L7 재현**: FI-14에서 생산자 `SENT` 잔류 234건(228/234/243)이 ack 콜백 실패 수와 같다. 알림 서비스에서는 전부 발송됐다.
- **SG-01+**: ServiceDown을 46.2s에 감지했다. SG-01만으로는 감지 수단이 없다.

결과 문서: `docs/04-experiments/results/phase1-sg01.md`.

### 커밋 (main..HEAD, 마감 docs 커밋 제외)
```
1e4dd05 docs(experiments): Phase 1 SG-01·SG-01+ 실험 결과와 가설 판정
6e1ace6 feat(harness): 실험 실행 결과를 시나리오별 중앙값·반복별 표로 집계하는 스크립트
09b2f53 fix(sg01): 리뷰 반영 — ack 미수신 재발송, 실패 status 회신, 측정 정확도 보정
8e1852f feat(obs): SG-01+ 운영 경로(Prometheus·Alertmanager·Grafana)와 측정 충실도 보정
9392352 feat(harness): k6 시나리오, 정답 대조 analyzer, 실험 러너
12e6662 feat(services): 동기 REST 흐름 4개 서비스와 SG-01 기준선, mock-slack, compose 인프라
1208e85 docs(plans): Phase 0~1 하네스·기준선 요구사항 명세 및 작업 로그
```
변경 규모: 109 files, +6755 (전부 신규).

### 핵심 diff (전부 신규 — HEAD 1e4dd05 실파일 복사, 워커 작성 · 메인 2건 대조 확인)
① SG-01 생산자 송신. `alert_status`를 기록한 뒤 전송한다. 업무 커밋과 이 기록 사이가 dual-write 공백(L6)이다. `services/shipping-service/src/main/kotlin/dev/alertsystem/shipping/alerting/Alerting.kt:100-107`
```kotlin
        jdbc.sql(
            """
            insert into alert_status(msg_id, payload, status, retry_count, created_at, updated_at)
            values (?, ?::jsonb, 'SENT', 0, now(), now())
            """.trimIndent(),
        ).params(payload.msgId, mapper.writeValueAsString(payload)).update()
        client.send(payload)
        if (ctx.has("dup-send")) client.send(payload) // FI-14: 같은 msgId 이중 전송
```
② ack 미수신 재발송(L1, 상한 maxResends). `Alerting.kt:115-119`
```kotlin
            update alert_status set ack_resend_count = ack_resend_count + 1, updated_at = now()
            where msg_id in (
                select msg_id from alert_status
                where status = 'SENT' and ack_resend_count < ? and updated_at < now() - make_interval(secs => ?)
                order by updated_at limit 200 for update skip locked
```
③ 알림 서비스 발송·회신과 재시도 배치 임계(초과 시). `services/notification-service/src/main/kotlin/dev/alertsystem/notification/dispatch/Dispatch.kt:76-79, 98-101`
```kotlin
        val delivered = results.all { it }
        if (delivered) inbox.markSent(row.msgId) else inbox.markFailed(row.msgId)
        val status = if (delivered) "ACKED" else "FAILED"
        val acked = callbacks.ack(row.callbackUrl, row.msgId, status)
```
```kotlin
    fun runOnce(): Int {
        val failed = inbox.failed()
        lastFailed.set(failed.size.toLong())
        if (failed.size <= threshold) return 0
```
④ inbox 수신 dedup. 실제로 중복을 막는 장치이면서 L7 분기의 원인이다. `services/notification-service/src/main/kotlin/dev/alertsystem/notification/inbox/Inbox.kt:40-43`
```sql
            on conflict (msg_id) do update set
                received_count = inbox.received_count + 1,
                status = case when inbox.status = 'RETRY_REQUESTED' then 'RECEIVED' else inbox.status end,
                updated_at = now()
```
⑤ analyzer 유실 판정(정답 대비 sink 수락 기록). `tools/analyzer/analyze.go:256-267`
```go
	for k, w := range want {
		if accepted[k] > 0 {
			a.Delivered++
			a.ByFault[w.fault].Delivered++
			e2e = append(e2e, float64(firstAccepted[k].Sub(w.at).Milliseconds()))
		} else {
			a.Lost++
			if len(a.LostSamples) < 10 {
				a.LostSamples = append(a.LostSamples, k)
			}
		}
	}
```

### task 상태
| task | 상태 |
|------|------|
| 01 골격 + compose + A1 smoke | 완료 — A1 반증 → mem 768m·order cpus 4·HTTP/1.1 |
| 02 동기 REST 흐름 + 장애 주입 | 완료 |
| 03 mock-slack | 완료 — 본문 `ok` 충실도 수정 |
| 04 SG-01 원안 | 완료 — 서비스 테스트 31건 통과 |
| 05 k6 + analyzer + I3 → Phase 0 | 완료 — K0 100/100, A2 실증 |
| 06 SG-01+ obs | 완료 — ServiceDown FIRING→RESOLVED |
| 07 smoke + Phase 1 ×3 + 결과 문서 | 완료 — H1·H2 채택, H3 이연 |
| 08 듀얼 리뷰 + 안전선 + PR | 리뷰·안전선 ①~⑥ 완료 · **PR 생성은 사용자 확인 대기** |

### 남은 항목
- PR 생성(feat/phase0-1-harness-baseline → main)과 push는 사용자 확인 후 진행한다.
- 열린 질문은 리뷰 ledger 끝에 있다. 채널 실패 운영 감지와 H3 판정은 NEXT.md N1·N2로 넘긴다.
- 워커 packet: base 736d9b9 / HEAD 1e4dd05 / 읽은 파일 log·spec·결과문서·Alerting.kt·Dispatch.kt·Inbox.kt·analyze.go·harness.js / 읽기 전용 git·sed 명령만 실행.
