package main

import (
	"bufio"
	"encoding/json"
	"io"
	"math"
	"sort"
	"strings"
	"time"
)

// ── 입력 ──

type Exp struct {
	Service   string `json:"service"`
	ErrorCode string `json:"errorCode"`
}

// k6 harness가 요청마다 남기는 정답 한 줄 (loadtest/k6/lib/harness.js).
type Expected struct {
	Type       string    `json:"type"`
	T          time.Time `json:"t"`
	TestRunID  string    `json:"testRunId"`
	RequestID  string    `json:"requestId"`
	Fault      string    `json:"fault"`
	Stage      string    `json:"stage"`
	Expect     []Exp     `json:"expect"`
	Unreached  []Exp     `json:"unreached"` // 주입했으나 대상 서비스에 도달하지 못해 실패가 발생하지 않은 건
	Unknown    []Exp     `json:"unknown"`   // 타임아웃 등으로 발생 여부를 응답으로 확정할 수 없는 건
	Status     int       `json:"status"`
	DurationMs float64   `json:"durationMs"`
}

// mock-slack 수신 기록 (tools/mock-slack).
type Record struct {
	Seq        int64           `json:"seq"`
	ReceivedAt time.Time       `json:"receivedAt"`
	Mode       string          `json:"mode"`
	Status     int             `json:"status"`
	Body       json.RawMessage `json:"body"`
	Error      string          `json:"error,omitempty"` // sink가 본문을 끝까지 받지 못한 경우
}

// 러너가 남기는 장애 주입 이벤트 (scripts/run-experiment.sh).
type Event struct {
	T      time.Time `json:"t"`
	Event  string    `json:"event"`
	Detail string    `json:"detail,omitempty"`
	Expect string    `json:"expect,omitempty"` // 이 장애를 감지했다고 볼 운영 알림 제목 패턴 (예: "ServiceDown shipping")
}

type domainBody struct {
	Metadata struct {
		EventPayload struct {
			MsgID     string `json:"msgId"`
			Service   string `json:"service"`
			ErrorCode string `json:"errorCode"`
			TestRunID string `json:"testRunId"`
			RequestID string `json:"requestId"`
		} `json:"event_payload"`
	} `json:"metadata"`
	Attachments []struct {
		Title string `json:"title"`
	} `json:"attachments"`
}

// ── 출력 ──

type Latency struct {
	P50 float64 `json:"p50"`
	P95 float64 `json:"p95"`
	P99 float64 `json:"p99"`
	Max float64 `json:"max"`
}

type FlowStats struct {
	Requests         int            `json:"requests"`
	ByStatus         map[string]int `json:"byStatus"`
	UnexpectedErrors int            `json:"unexpectedErrors"` // 실패를 주입하지 않았는데 201이 아닌 요청
	LatencyMs        Latency        `json:"latencyMs"`
}

type FaultStats struct {
	Expected  int `json:"expected"`
	Delivered int `json:"delivered"`
}

type AlertStats struct {
	Expected      int                    `json:"expected"`
	Unreached     int                    `json:"unreached"`
	Unknown       int                    `json:"unknown"`
	UnknownSeen   int                    `json:"unknownDelivered"` // 결과 불명 건 중 알림이 도달한 수 (= 실제로 발생했던 실패)
	Delivered     int                    `json:"delivered"`        // 정답 키 중 1회 이상 수락(200)된 수
	Lost          int                    `json:"lost"`
	Coverage      float64                `json:"coverage"`
	Duplicates    int                    `json:"duplicates"` // 같은 키의 2번째 이후 수락
	Unexpected    int                    `json:"unexpected"` // 이 run의 알림인데 정답에 없는 키
	Volume        int                    `json:"volume"`     // 이 run에서 사람이 본(수락된) 알림 메시지 수
	Rejected      int                    `json:"rejected"`   // 채널이 거절한(200 아님) 시도 수
	E2EMs         Latency                `json:"e2eMs"`      // 요청 시각 → 첫 수락 시각
	ByFault       map[string]*FaultStats `json:"byFault"`
	LostSamples   []string               `json:"lostSamples"`
	UnexpectedIDs []string               `json:"unexpectedSamples"`
}

type OpsAlert struct {
	ReceivedAt time.Time `json:"receivedAt"`
	Title      string    `json:"title"`
	Status     int       `json:"status"`
}

type Detection struct {
	Event         string   `json:"event"`
	EventAt       string   `json:"eventAt"`
	FirstOpsAlert string   `json:"firstOpsAlert,omitempty"`
	Seconds       *float64 `json:"seconds"`
}

type Report struct {
	RunID      string               `json:"runId"`
	Flow       FlowStats            `json:"flow"`
	FlowStages map[string]FlowStats `json:"flowStages"`
	Alerts     AlertStats           `json:"alerts"`
	Ops        []OpsAlert           `json:"opsAlerts"`
	SinkErrors int                  `json:"sinkReadErrors"` // 본문 수신 실패로 어떤 알림인지 알 수 없는 기록 (측정 불확실성)
	Detections []Detection          `json:"detections"`
	Events     []Event              `json:"events"`
	Skipped    int                  `json:"skippedLines"`
}

// ── 파싱 ──

// 정답 로그: k6 raw 로그라 "Request Failed" 같은 비JSON 행이 섞인다 → type=expected만 채택.
func ReadExpected(r io.Reader, runID string) ([]Expected, int, error) {
	var out []Expected
	skipped := 0
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		var e Expected
		if err := json.Unmarshal(sc.Bytes(), &e); err != nil || e.Type != "expected" {
			skipped++
			continue
		}
		if e.TestRunID == runID {
			out = append(out, e)
		}
	}
	return out, skipped, sc.Err()
}

func ReadRecords(r io.Reader) ([]Record, error) {
	var out []Record
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	for sc.Scan() {
		var rec Record
		if err := json.Unmarshal(sc.Bytes(), &rec); err != nil {
			return nil, err // sink 기록이 깨졌으면 측정 무효 — 숨기지 않는다
		}
		out = append(out, rec)
	}
	return out, sc.Err()
}

func ReadEvents(r io.Reader) ([]Event, error) {
	var out []Event
	sc := bufio.NewScanner(r)
	for sc.Scan() {
		if strings.TrimSpace(sc.Text()) == "" {
			continue
		}
		var e Event
		if err := json.Unmarshal(sc.Bytes(), &e); err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, sc.Err()
}

// ── 분석 ──

func key(requestID, service, code string) string { return requestID + "|" + service + "|" + code }

func Analyze(runID string, expected []Expected, records []Record, events []Event) Report {
	rep := Report{RunID: runID, Events: events, FlowStages: map[string]FlowStats{}}
	rep.Flow = flowStats(expected)
	byStage := map[string][]Expected{}
	for _, e := range expected {
		byStage[e.Stage] = append(byStage[e.Stage], e)
	}
	for stage, es := range byStage {
		rep.FlowStages[stage] = flowStats(es)
	}

	type exp struct {
		at    time.Time
		fault string
	}
	want := map[string]exp{}
	unknown := map[string]bool{}
	a := AlertStats{ByFault: map[string]*FaultStats{}}
	for _, e := range expected {
		a.Unreached += len(e.Unreached)
		for _, x := range e.Unknown {
			unknown[key(e.RequestID, x.Service, x.ErrorCode)] = true
		}
		for _, x := range e.Expect {
			k := key(e.RequestID, x.Service, x.ErrorCode)
			want[k] = exp{e.T, e.Fault}
			fs := a.ByFault[e.Fault]
			if fs == nil {
				fs = &FaultStats{}
				a.ByFault[e.Fault] = fs
			}
			fs.Expected++
		}
	}
	a.Expected = len(want)
	a.Unknown = len(unknown)

	accepted := map[string]int{}
	firstAccepted := map[string]time.Time{}
	for _, rec := range records {
		if rec.Error != "" {
			rep.SinkErrors++
			continue
		}
		var b domainBody
		_ = json.Unmarshal(rec.Body, &b)
		p := b.Metadata.EventPayload
		if p.MsgID == "" {
			if len(b.Attachments) > 0 { // Alertmanager Slack 형식 = 운영 경로 알림
				rep.Ops = append(rep.Ops, OpsAlert{rec.ReceivedAt, b.Attachments[0].Title, rec.Status})
			}
			continue
		}
		if p.TestRunID != runID {
			continue
		}
		if rec.Status != 200 {
			a.Rejected++
			continue
		}
		a.Volume++
		k := key(p.RequestID, p.Service, p.ErrorCode)
		if accepted[k] == 0 {
			firstAccepted[k] = rec.ReceivedAt
		}
		accepted[k]++
	}

	var e2e []float64
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
	for k, n := range accepted {
		if n > 1 {
			a.Duplicates += n - 1
		}
		if unknown[k] {
			a.UnknownSeen++
			continue
		}
		if _, ok := want[k]; !ok {
			a.Unexpected++
			if len(a.UnexpectedIDs) < 10 {
				a.UnexpectedIDs = append(a.UnexpectedIDs, k)
			}
		}
	}
	sort.Strings(a.LostSamples)
	sort.Strings(a.UnexpectedIDs)
	if a.Expected > 0 {
		a.Coverage = float64(a.Delivered) / float64(a.Expected)
	}
	a.E2EMs = latency(e2e)
	rep.Alerts = a
	rep.Detections = detections(events, rep.Ops)
	return rep
}

func flowStats(es []Expected) FlowStats {
	f := FlowStats{Requests: len(es), ByStatus: map[string]int{}}
	var d []float64
	for _, e := range es {
		f.ByStatus[itoa(e.Status)]++
		if len(e.Expect) == 0 && e.Status != 201 {
			f.UnexpectedErrors++
		}
		d = append(d, e.DurationMs)
	}
	f.LatencyMs = latency(d)
	return f
}

// 각 장애 이벤트 이후 처음 도착한 운영 경로 알림까지의 시간 (감지 시간).
func detections(events []Event, ops []OpsAlert) []Detection {
	var out []Detection
	for _, ev := range events {
		// 기대 알림 패턴이 있는 장애만 감지 시간을 잰다 (없으면 무관한 알림이 '감지'로 잡힌다)
		if !strings.HasPrefix(ev.Event, "fault:") || ev.Expect == "" {
			continue
		}
		d := Detection{Event: ev.Event + " " + ev.Detail, EventAt: ev.T.UTC().Format(time.RFC3339)}
		for _, o := range ops {
			if o.ReceivedAt.After(ev.T) && o.Status == 200 && strings.Contains(strings.ToUpper(o.Title), "FIRING") &&
				strings.Contains(o.Title, ev.Expect) {
				s := o.ReceivedAt.Sub(ev.T).Seconds()
				d.FirstOpsAlert, d.Seconds = o.Title, &s
				break
			}
		}
		out = append(out, d)
	}
	return out
}

func latency(v []float64) Latency {
	if len(v) == 0 {
		return Latency{}
	}
	s := append([]float64(nil), v...)
	sort.Float64s(s)
	q := func(p float64) float64 {
		i := int(math.Ceil(p*float64(len(s)))) - 1
		if i < 0 {
			i = 0
		}
		return s[i]
	}
	return Latency{P50: q(0.50), P95: q(0.95), P99: q(0.99), Max: s[len(s)-1]}
}

func itoa(n int) string {
	b, _ := json.Marshal(n)
	return string(b)
}
