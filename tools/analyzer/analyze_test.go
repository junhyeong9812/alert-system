package main

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

// 합성 데이터셋으로 analyzer 정확도를 검증한다 (spec I3 — 측정 도구 선검증).

var t0 = time.Date(2026, 11, 3, 12, 0, 0, 0, time.UTC)

func exp(id, fault string, status int, x ...Exp) Expected {
	return Expected{Type: "expected", T: t0, TestRunID: "run", RequestID: id, Fault: fault, Expect: x, Status: status, DurationMs: 10}
}

func rec(id, svc, code, run string, status int, after time.Duration) Record {
	body, _ := json.Marshal(map[string]any{
		"text": "x",
		"metadata": map[string]any{"event_type": "alert", "event_payload": map[string]any{
			"msgId": "m-" + id, "service": svc, "errorCode": code, "testRunId": run, "requestId": id,
		}},
	})
	return Record{ReceivedAt: t0.Add(after), Status: status, Body: body}
}

func ops(title string, after time.Duration) Record {
	body, _ := json.Marshal(map[string]any{"attachments": []map[string]any{{"title": title}}})
	return Record{ReceivedAt: t0.Add(after), Status: 200, Body: body}
}

var biz = Exp{"shipping", "ADDRESS_INVALID"}

func TestPerfectDelivery(t *testing.T) {
	r := Analyze("run",
		[]Expected{exp("a", "shipping:biz", 422, biz), exp("b", "shipping:biz", 422, biz), exp("c", "", 201)},
		[]Record{rec("a", "shipping", "ADDRESS_INVALID", "run", 200, time.Second), rec("b", "shipping", "ADDRESS_INVALID", "run", 200, 2*time.Second)},
		nil)
	a := r.Alerts
	if a.Expected != 2 || a.Delivered != 2 || a.Lost != 0 || a.Coverage != 1 || a.Duplicates != 0 || a.Unexpected != 0 || a.Volume != 2 {
		t.Fatalf("unexpected stats %+v", a)
	}
	if a.E2EMs.P50 != 1000 || a.E2EMs.Max != 2000 {
		t.Errorf("e2e wrong: %+v", a.E2EMs)
	}
	if r.Flow.Requests != 3 || r.Flow.UnexpectedErrors != 0 {
		t.Errorf("flow wrong: %+v", r.Flow)
	}
}

func TestLossIsCountedAndSampled(t *testing.T) {
	r := Analyze("run",
		[]Expected{exp("a", "shipping:biz", 422, biz), exp("b", "shipping:biz", 422, biz)},
		[]Record{rec("a", "shipping", "ADDRESS_INVALID", "run", 200, time.Second)}, nil)
	if r.Alerts.Lost != 1 || r.Alerts.Coverage != 0.5 || r.Alerts.LostSamples[0] != "b|shipping|ADDRESS_INVALID" {
		t.Fatalf("loss not detected: %+v", r.Alerts)
	}
	if r.Alerts.ByFault["shipping:biz"].Expected != 2 || r.Alerts.ByFault["shipping:biz"].Delivered != 1 {
		t.Errorf("byFault wrong: %+v", r.Alerts.ByFault["shipping:biz"])
	}
}

func TestDuplicatesAndRejectedAttempts(t *testing.T) {
	r := Analyze("run",
		[]Expected{exp("a", "shipping:biz", 422, biz)},
		[]Record{
			rec("a", "shipping", "ADDRESS_INVALID", "run", 500, time.Second), // 채널 거절 — 도달 아님
			rec("a", "shipping", "ADDRESS_INVALID", "run", 200, 3*time.Second),
			rec("a", "shipping", "ADDRESS_INVALID", "run", 200, 4*time.Second), // 중복
		}, nil)
	a := r.Alerts
	if a.Delivered != 1 || a.Duplicates != 1 || a.Rejected != 1 || a.Volume != 2 {
		t.Fatalf("dup/rejected wrong: %+v", a)
	}
	if a.E2EMs.P50 != 3000 {
		t.Errorf("e2e should use first accepted record, got %+v", a.E2EMs)
	}
}

func TestOtherRunsAreIgnoredAndUnknownKeysAreUnexpected(t *testing.T) {
	r := Analyze("run",
		[]Expected{exp("a", "shipping:biz", 422, biz)},
		[]Record{
			rec("a", "shipping", "ADDRESS_INVALID", "run", 200, time.Second),
			rec("z", "shipping", "ADDRESS_INVALID", "other-run", 200, time.Second),
			rec("x", "order", "TECH_ERROR", "run", 200, time.Second),
		}, nil)
	if r.Alerts.Unexpected != 1 || r.Alerts.Volume != 2 || r.Alerts.UnexpectedIDs[0] != "x|order|TECH_ERROR" {
		t.Fatalf("filtering wrong: %+v", r.Alerts)
	}
}

func TestMultipleExpectedAlertsPerRequest(t *testing.T) {
	r := Analyze("run",
		[]Expected{exp("a", "shipping:biz,product:compensation-fail", 502, biz, Exp{"product", "COMPENSATION_FAILED"})},
		[]Record{rec("a", "shipping", "ADDRESS_INVALID", "run", 200, time.Second)}, nil)
	if r.Alerts.Expected != 2 || r.Alerts.Lost != 1 || r.Alerts.LostSamples[0] != "a|product|COMPENSATION_FAILED" {
		t.Fatalf("multi-expect wrong: %+v", r.Alerts)
	}
}

func TestFlowUnexpectedErrorsOnlyForUnfaultedRequests(t *testing.T) {
	r := Analyze("run", []Expected{exp("a", "", 502), exp("b", "", 0), exp("c", "shipping:biz", 422, biz), exp("d", "shipping:latency=300", 201)}, nil, nil)
	if r.Flow.UnexpectedErrors != 2 {
		t.Fatalf("want 2 unexpected flow errors, got %d", r.Flow.UnexpectedErrors)
	}
}

func TestReadExpectedSkipsNoiseAndOtherRuns(t *testing.T) {
	in := strings.Join([]string{
		`Request Failed`,
		`{"type":"expected","t":"2026-11-03T12:00:00Z","testRunId":"run","requestId":"a","expect":[],"status":201}`,
		`{"type":"expected","t":"2026-11-03T12:00:00Z","testRunId":"warmup","requestId":"w","expect":[],"status":201}`,
		`{"type":"other"}`,
	}, "\n")
	es, skipped, err := ReadExpected(strings.NewReader(in), "run")
	if err != nil || len(es) != 1 || es[0].RequestID != "a" || skipped != 2 {
		t.Fatalf("got %v skipped=%d err=%v", es, skipped, err)
	}
}

func TestReadRecordsFailsLoudlyOnCorruptSink(t *testing.T) {
	if _, err := ReadRecords(strings.NewReader("{not json}\n")); err == nil {
		t.Fatal("corrupt sink record must be an error, not silently skipped")
	}
}

func TestOpsAlertsAndDetectionTime(t *testing.T) {
	events := []Event{{T: t0, Event: "fault:stop", Detail: "shipping-service"}, {T: t0.Add(time.Hour), Event: "fault:stop", Detail: "never"}}
	r := Analyze("run", []Expected{exp("a", "", 201)},
		[]Record{ops("[RESOLVED] ServiceDown", 10*time.Second), ops("[FIRING:1] ServiceDown shipping", 45*time.Second)}, events)
	if len(r.Ops) != 2 {
		t.Fatalf("want 2 ops alerts, got %d", len(r.Ops))
	}
	if r.Detections[0].Seconds == nil || *r.Detections[0].Seconds != 45 {
		t.Fatalf("detection wrong: %+v", r.Detections[0])
	}
	if r.Detections[1].Seconds != nil {
		t.Errorf("second event must be undetected: %+v", r.Detections[1])
	}
}

func TestUnreachedInjectionsAreReportedButNotExpected(t *testing.T) {
	e := exp("a", "shipping:biz", 502)
	e.Unreached = []Exp{biz}
	r := Analyze("run", []Expected{e}, nil, nil)
	if r.Alerts.Expected != 0 || r.Alerts.Unreached != 1 || r.Alerts.Lost != 0 {
		t.Fatalf("unreached must not count as loss: %+v", r.Alerts)
	}
}
