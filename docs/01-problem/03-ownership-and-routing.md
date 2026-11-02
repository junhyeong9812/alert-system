# 실패 유형별 담당과 라우팅

> "누가 감지하고, 누가 전달하고, 누가 대응하는가"를 실패 유형별로 정한다.
> 최초 설계의 주객전도(P1)는 이 세 역할을 분리하지 않은 데서 나왔다.

관련: [실패 유형 분류](02-failure-taxonomy.md) · [최초 설계 리뷰](01-initial-design-review.md)

---

## 1. 원칙

1. **감지 ≠ 전달 ≠ 대응.** 세 역할은 서로 다른 주체가 맡을 수 있고, 대개 그래야 한다.
   - **감지 주체**: 실패를 "사실"로 인식하는 시스템 (서비스, 워치독, 모니터링)
   - **전달 주체**: 사실을 사람에게 보내는 시스템 (알림 서비스)
   - **대응 주체**: 알림을 받고 조치하는 사람/팀
2. **생산자의 책임은 "사실을 내구성 있게 넘기는 것"까지.** 넘긴 뒤의 전달·재시도·채널 선택은 알림 컨텍스트의 책임이다.
3. **알림 경로의 감시는 알림 경로 바깥에서.** 자기 자신을 감시할 수 없다.
4. **증상보다 원인.** 원인 알림이 발화 중이면 그로 인한 증상 알림은 억제한다.
5. **흐름에는 명시적 소유자가 필요하다.** 코레오그래피에서는 흐름 전체를 아는 주체가 없으므로, 흐름 감시자(워치독)와 그 소유팀을 지정한다.

---

## 2. 역할 매트릭스

| 유형 | 감지 주체 (시스템) | 전달 주체 | 1차 대응 (사람) | 심각도 · 채널 |
|------|-------------------|----------|----------------|--------------|
| **F1** 비즈니스 | 도메인 서비스 (이벤트 발행) + 비율 규칙 | 알림 서비스 | 도메인 운영팀 / CS — 비율 이상이면 서비스 개발팀 | info~warn · Slack `#domain-ops` |
| **F2** 기술 예외 | 서비스 (예외 이벤트) + 에러율 메트릭 | 알림 서비스 | 해당 서비스 개발팀 | warn (급증 시 critical) · Slack `#svc-<name>` |
| **F3** 흐름 전파 | 오케스트레이터 (호출자) | 알림 서비스 | 흐름 소유팀 (오케스트레이터 소유 = 주문팀) | warn · Slack `#flow-order` |
| **F4** 흐름 부재 | Saga 워치독 / Process Manager | 알림 서비스 | 흐름 소유팀 | warn → 기한 2배 초과 시 critical |
| **F5** 보상 실패 | 오케스트레이터 / 워치독 / Sweeper | 알림 서비스 | 흐름 소유팀 + 데이터 담당 (수동 개입) | critical · Slack + Email |
| **F6** 시스템 | 외부 모니터링 (Prometheus, Blackbox) | Alertmanager → 알림 서비스 | on-call / SRE | critical · Slack + Email |
| **F7** 알림 경로 | 외부 dead man's switch + 알림 서비스 메트릭 | **독립 경로** (알림 서비스를 거치지 않음) | 플랫폼팀 | critical · 외부 서비스가 직접 Email |

> F6을 알림 서비스로 합류시킬지, Alertmanager가 직접 채널로 보낼지는 트레이드오프다.
> 합류시키면 라우팅·묶음 정책을 하나로 관리하지만, 알림 서비스 장애가 F6 전달까지 막는다.
> → 방법론별로 다르게 그리고, 실험으로 판단한다([SY-01](../03-methodologies/system/sy-01-level-triggered-metrics.md)).

---

## 3. DDD 관점 — 컨텍스트 맵

```
 ┌───────────────┐  ┌───────────────┐  ┌───────────────┐
 │ Order 컨텍스트 │  │Product 컨텍스트│  │Shipping 컨텍스트│      Core / Supporting 도메인
 └───────┬───────┘  └───────┬───────┘  └───────┬───────┘
         │  Published Language: FailureOccurred 이벤트 (공용 스키마)
         │  (Upstream — 사실을 발행할 뿐, 누가 소비하는지 모름)
         ▼                  ▼                  ▼
 ┌─────────────────────────────────────────────────────┐
 │ Notification 컨텍스트  (Generic Subdomain, Downstream)│
 │  ACL: FailureOccurred → Alert(fingerprint, severity, owner)
 │  Alert 집계 → 라우팅 정책 → Notifier(Slack | Email)  │
 └─────────────────────────────────────────────────────┘
      ✕ Downstream → Upstream 명령 없음 ("재시도해라" 금지)
```

- 비즈니스 서비스는 **Upstream**이다. `FailureOccurred` 같은 공용 이벤트(Published Language)를 발행할 뿐, 알림의 존재를 모른다.
- 알림 서비스는 **Downstream**이다. ACL(Anti-Corruption Layer)로 도메인 이벤트를 자기 모델(`Alert`)로 번역한다. 도메인 서비스의 이벤트 스키마가 바뀌어도 ACL만 고친다.
- **Downstream이 Upstream에게 명령하지 않는다.** 최초 설계의 "재시도해라"는 이 방향을 거스른다.

### 생산자는 어디까지 책임지나

```
 Order 서비스
   BEGIN
     UPDATE orders ...                 ← 비즈니스 변경
     INSERT outbox(FailureOccurred)    ← 사실 기록 (같은 트랜잭션)
   COMMIT
   relay: outbox → 브로커/알림 서비스   ← 수락(202/ack) 받을 때까지 재발행
                                         ════ 여기서 Order 책임 종료 ════
```

- relay의 재발행은 **명령을 받아서 하는 재시도가 아니라** 자기 사실을 넘기는 자기 책임이다. 방향이 단방향이다.

---

## 4. 알림 실패 처리 — 책임 사다리

알림 발송이 실패했을 때 누가 무엇을 하는가. **어느 단계에도 생산자는 등장하지 않는다.**

```
 ① 알림 서비스 내부 재시도 (지수 백오프 + jitter, 최대 N회)
        │ 실패
        ▼
 ② 채널 fallback (Slack 실패 → Email)
        │ 실패
        ▼
 ③ DLQ 적재 (원본 + 실패 사유 + 시도 이력 보존)
        │
        ▼
 ④ DLQ 적재량 · 발송 실패율 = 메트릭 노출 → 운영 경로(SY-01)가 감지 → 플랫폼팀
        │
        ▼
 ⑤ 수동 재처리 (DLQ replay 도구 — 채널 복구 후 일괄 재발송)

 ⑥ 메타 감시: 알림 경로 전체가 죽으면 ①~⑤ 모두 멈춤 → SY-03 dead man's switch
```

| 상황 | 처리 주체 | 생산자 개입 |
|------|----------|------------|
| 채널 일시 장애 (Slack 5xx) | ① 재시도 | 없음 |
| 채널 지속 장애 | ② fallback → ③ DLQ | 없음 |
| 채널 rate limit (429) | ① 백오프 + 묶음 처리 강화 | 없음 |
| 알림 서비스가 수신 **후** 크래시 | 재시작 후 내구 저장소(inbox/오프셋)에서 재개 | 없음 |
| 알림 서비스가 수신 **전** 유실 | 생산자 relay가 수락 미확인으로 재발행 | **자기 책임으로 재발행** (명령 아님) |
| 알림 경로 전체 사망 | 외부 dead man's switch | 없음 |

---

## 5. 라우팅 정책 (추상화)

### 알림 모델
```
Alert {
  fingerprint   // 문제 정체성 — hash(service, errorCode, severity, …)
  severity      // info | warn | critical
  owner         // 대응 팀 (유형 + 서비스로 결정)
  category      // F1 ~ F7
  summary, count, firstSeen, lastSeen, samples[]   // 묶음 처리 결과
  traceIds[]    // 흐름 진단용 (FL-04)
}
```

### 채널 추상화
```
interface Notifier {
  send(alert: Alert): Result     // 성공 / 재시도 가능 실패 / 영구 실패
}
SlackNotifier, EmailNotifier, (향후) PagerNotifier …

RoutingPolicy: (severity, owner, category) → [Notifier...] + 묶음 윈도우 + 재알림 주기
```

### 심각도별 기본 정책 (실험 전 초안)

| severity | 채널 | 묶음 윈도우 | 재알림 | 비고 |
|----------|------|------------|--------|------|
| info | Slack | 5분 | 없음 | 요약만 |
| warn | Slack | 1분 | 30분마다 (지속 시) | |
| critical | Slack + Email | 즉시 (묶음 우회) | 10분마다 (해소까지) | 첫 건은 지연 없이 발송 |

- 해소(resolved) 알림도 보낸다. "언제 끝났는지"를 모르면 대응자가 계속 확인해야 한다.
- 수치는 실험에서 조정한다([실험 계획](../04-experiments/experiment-plan.md)).
