package main

import (
	"bufio"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func newTestServer(t *testing.T) (*Server, *httptest.Server) {
	t.Helper()
	s, err := NewServer(filepath.Join(t.TempDir(), "records.jsonl"))
	if err != nil {
		t.Fatal(err)
	}
	s.sleep = func(time.Duration) {}
	ts := httptest.NewServer(s.Handler())
	t.Cleanup(ts.Close)
	return s, ts
}

func post(t *testing.T, url, body string) int {
	t.Helper()
	res, err := http.Post(url, "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	return res.StatusCode
}

func readRecords(t *testing.T, base string) []Record {
	t.Helper()
	res, err := http.Get(base + "/_control/records")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	var out []Record
	sc := bufio.NewScanner(res.Body)
	for sc.Scan() {
		var r Record
		if err := json.Unmarshal(sc.Bytes(), &r); err != nil {
			t.Fatal(err)
		}
		out = append(out, r)
	}
	return out
}

func TestModesReturnExpectedStatus(t *testing.T) {
	_, ts := newTestServer(t)
	cases := map[string]int{"ok": 200, "5xx": 500, "429": 429, "latency": 200, "blackhole": 504}
	for mode, want := range cases {
		post(t, ts.URL+"/_control/mode", `{"mode":"`+mode+`","latencyMs":10}`)
		if got := post(t, ts.URL+"/webhook", `{"text":"x"}`); got != want {
			t.Errorf("mode %s: got %d want %d", mode, got, want)
		}
	}
	if got := post(t, ts.URL+"/_control/mode", `{"mode":"nope"}`); got != 400 {
		t.Errorf("unknown mode should be 400, got %d", got)
	}
}

// spec I7: 거절한 요청까지 모두 기록한다.
func TestRecordsEveryRequestIncludingRejected(t *testing.T) {
	_, ts := newTestServer(t)
	post(t, ts.URL+"/webhook", `{"n":1}`)
	post(t, ts.URL+"/_control/mode", `{"mode":"5xx"}`)
	post(t, ts.URL+"/webhook", `{"n":2}`)
	post(t, ts.URL+"/webhook", `not-json`)

	recs := readRecords(t, ts.URL)
	if len(recs) != 3 {
		t.Fatalf("want 3 records, got %d", len(recs))
	}
	if recs[0].Status != 200 || recs[1].Status != 500 || recs[1].Mode != "5xx" {
		t.Errorf("unexpected statuses: %+v", recs)
	}
	if string(recs[2].Body) != `"not-json"` {
		t.Errorf("non-JSON body should be stored as a JSON string, got %s", recs[2].Body)
	}
}

func TestConcurrentRequestsAreAllRecordedWithUniqueSeq(t *testing.T) {
	_, ts := newTestServer(t)
	const n = 500
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			post(t, ts.URL+"/webhook", `{"text":"x"}`)
		}()
	}
	wg.Wait()
	recs := readRecords(t, ts.URL)
	if len(recs) != n {
		t.Fatalf("want %d records, got %d", n, len(recs))
	}
	seen := map[int64]bool{}
	for _, r := range recs {
		if seen[r.Seq] {
			t.Fatalf("duplicate seq %d", r.Seq)
		}
		seen[r.Seq] = true
	}
}

func TestResetClearsRecordsAndMode(t *testing.T) {
	_, ts := newTestServer(t)
	post(t, ts.URL+"/_control/mode", `{"mode":"5xx"}`)
	post(t, ts.URL+"/webhook", `{}`)
	post(t, ts.URL+"/_control/reset", ``)
	if recs := readRecords(t, ts.URL); len(recs) != 0 {
		t.Fatalf("want 0 records after reset, got %d", len(recs))
	}
	if got := post(t, ts.URL+"/webhook", `{}`); got != 200 {
		t.Errorf("mode should be ok after reset, got %d", got)
	}
	if recs := readRecords(t, ts.URL); len(recs) != 1 || recs[0].Seq != 1 {
		t.Errorf("seq should restart at 1: %+v", recs)
	}
}
