// analyzer: 실험 정답(k6 expected 로그)과 sink 수신 기록(mock-slack)을 대조해 유실·중복·지연·감지 시간을 계산한다.
//
//	analyzer -run-id <id> -expected expected.log -records records.jsonl [-events events.jsonl] -out report.json -md report.md
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"sort"
	"strings"
)

func main() {
	runID := flag.String("run-id", "", "test run id (X-Test-Run-Id)")
	expectedPath := flag.String("expected", "", "k6 expected log")
	recordsPath := flag.String("records", "", "mock-slack records.jsonl")
	eventsPath := flag.String("events", "", "events.jsonl (optional)")
	outPath := flag.String("out", "report.json", "report json path")
	mdPath := flag.String("md", "", "report markdown path (optional)")
	flag.Parse()
	if *runID == "" || *expectedPath == "" || *recordsPath == "" {
		flag.Usage()
		os.Exit(2)
	}

	expected, skipped, err := ReadExpected(mustOpen(*expectedPath), *runID)
	check(err)
	records, err := ReadRecords(mustOpen(*recordsPath))
	check(err)
	var events []Event
	if *eventsPath != "" {
		if f, err := os.Open(*eventsPath); err == nil {
			events, err = ReadEvents(f)
			check(err)
		}
	}
	if len(expected) == 0 {
		log.Fatalf("no expected lines for run %q — refusing to report an empty run as success", *runID)
	}

	rep := Analyze(*runID, expected, records, events)
	rep.Skipped = skipped
	out, _ := json.MarshalIndent(rep, "", "  ")
	check(os.WriteFile(*outPath, out, 0o644))
	md := Markdown(rep)
	if *mdPath != "" {
		check(os.WriteFile(*mdPath, []byte(md), 0o644))
	}
	fmt.Print(md)
}

func Markdown(r Report) string {
	var b strings.Builder
	a := r.Alerts
	fmt.Fprintf(&b, "# %s\n\n", r.RunID)
	fmt.Fprintf(&b, "## 본 흐름\n\n| 요청 | 의도치 않은 실패 | p50 | p95 | p99 | max |\n|---|---|---|---|---|---|\n")
	fmt.Fprintf(&b, "| %d | %d | %.0fms | %.0fms | %.0fms | %.0fms |\n\n", r.Flow.Requests, r.Flow.UnexpectedErrors,
		r.Flow.LatencyMs.P50, r.Flow.LatencyMs.P95, r.Flow.LatencyMs.P99, r.Flow.LatencyMs.Max)
	fmt.Fprintf(&b, "상태 코드: %v\n\n", r.Flow.ByStatus)
	if len(r.FlowStages) > 1 {
		fmt.Fprintf(&b, "| 구간 | 요청 | 의도치 않은 실패 | p50 | p95 | p99 |\n|---|---|---|---|---|---|\n")
		for _, s := range sortedKeys(r.FlowStages) {
			f := r.FlowStages[s]
			fmt.Fprintf(&b, "| %s | %d | %d | %.0fms | %.0fms | %.0fms |\n", s, f.Requests, f.UnexpectedErrors, f.LatencyMs.P50, f.LatencyMs.P95, f.LatencyMs.P99)
		}
		b.WriteString("\n")
	}
	fmt.Fprintf(&b, "## 도메인 알림\n\n| 정답 | 미도달 주입 | 결과 불명(알림 도달) | 도달 | 유실 | coverage | 중복 | 예상 밖 | 사람이 본 알림 | 채널 거절 | e2e p50 | e2e p95 | e2e max |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
	fmt.Fprintf(&b, "| %d | %d | %d (%d) | %d | %d | %.2f%% | %d | %d | %d | %d | %.0fms | %.0fms | %.0fms |\n\n", a.Expected, a.Unreached, a.Unknown, a.UnknownSeen, a.Delivered, a.Lost,
		a.Coverage*100, a.Duplicates, a.Unexpected, a.Volume, a.Rejected, a.E2EMs.P50, a.E2EMs.P95, a.E2EMs.Max)
	if len(a.ByFault) > 0 {
		fmt.Fprintf(&b, "| 주입 | 정답 | 도달 |\n|---|---|---|\n")
		for _, f := range sortedKeys(a.ByFault) {
			fmt.Fprintf(&b, "| `%s` | %d | %d |\n", f, a.ByFault[f].Expected, a.ByFault[f].Delivered)
		}
		b.WriteString("\n")
	}
	if len(a.LostSamples) > 0 {
		fmt.Fprintf(&b, "유실 샘플: %v\n\n", a.LostSamples)
	}
	if len(r.Ops) > 0 {
		fmt.Fprintf(&b, "## 운영 경로 알림 (%d)\n\n", len(r.Ops))
		for _, o := range r.Ops {
			fmt.Fprintf(&b, "- %s · %d · %s\n", o.ReceivedAt.Format("15:04:05"), o.Status, o.Title)
		}
		b.WriteString("\n")
	}
	if len(r.Detections) > 0 {
		fmt.Fprintf(&b, "## 감지 시간\n\n| 이벤트 | 첫 운영 알림 | 초 |\n|---|---|---|\n")
		for _, d := range r.Detections {
			s := "미감지"
			if d.Seconds != nil {
				s = fmt.Sprintf("%.1f", *d.Seconds)
			}
			fmt.Fprintf(&b, "| %s | %s | %s |\n", d.Event, d.FirstOpsAlert, s)
		}
		b.WriteString("\n")
	}
	if len(r.Events) > 0 {
		fmt.Fprintf(&b, "## 이벤트\n\n")
		for _, e := range r.Events {
			fmt.Fprintf(&b, "- %s %s %s\n", e.T.Format("15:04:05"), e.Event, e.Detail)
		}
	}
	return b.String()
}

func sortedKeys[V any](m map[string]V) []string {
	k := make([]string, 0, len(m))
	for s := range m {
		k = append(k, s)
	}
	sort.Strings(k)
	return k
}

func mustOpen(p string) *os.File {
	f, err := os.Open(p)
	check(err)
	return f
}

func check(err error) {
	if err != nil {
		log.Fatal(err)
	}
}
