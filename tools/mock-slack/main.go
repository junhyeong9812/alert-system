// mock-slack: Slack Incoming Webhook 흉내 + 장애 모드 + 무손실 수신 기록 (spec I7).
//
//	POST /webhook             알림 수신 (모드에 따라 200 / 500 / 429 / 지연 / blackhole)
//	POST /_control/mode       {"mode":"ok|5xx|429|latency|blackhole","latencyMs":3000}
//	GET  /_control/records    수신 기록 전체 (JSONL)
//	POST /_control/reset      기록 초기화
//	GET  /_control/stats      응답 상태별 건수
//	GET  /healthz
package main

import (
	"bufio"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"sync"
	"time"
)

type Mode struct {
	Mode      string `json:"mode"`
	LatencyMs int    `json:"latencyMs"`
}

type Record struct {
	Seq        int64           `json:"seq"`
	ReceivedAt time.Time       `json:"receivedAt"`
	Mode       string          `json:"mode"`
	Status     int             `json:"status"`
	Body       json.RawMessage `json:"body"`
	Error      string          `json:"error,omitempty"`
}

type Server struct {
	mu      sync.Mutex
	mode    Mode
	seq     int64
	path    string
	file    *os.File
	writer  *bufio.Writer
	stats   map[int]int64
	timeNow func() time.Time
	sleep   func(time.Duration)
}

func NewServer(path string) (*Server, error) {
	s := &Server{mode: Mode{Mode: "ok"}, path: path, stats: map[int]int64{}, timeNow: time.Now, sleep: time.Sleep}
	return s, s.open(os.O_CREATE | os.O_APPEND | os.O_WRONLY)
}

func (s *Server) open(flags int) error {
	f, err := os.OpenFile(s.path, flags, 0o644)
	if err != nil {
		return err
	}
	s.file, s.writer = f, bufio.NewWriter(f)
	return nil
}

// statusFor: 모드별 응답 코드. blackhole은 응답하지 않고 오래 붙잡아 클라이언트 타임아웃을 유도한다.
func statusFor(m Mode) int {
	switch m.Mode {
	case "5xx":
		return http.StatusInternalServerError
	case "429":
		return http.StatusTooManyRequests
	case "blackhole":
		return http.StatusGatewayTimeout
	default:
		return http.StatusOK
	}
}

func (s *Server) webhook(w http.ResponseWriter, r *http.Request) {
	body, readErr := io.ReadAll(r.Body)
	if readErr != nil || !json.Valid(body) {
		raw, _ := json.Marshal(string(body))
		body = raw
	}
	s.mu.Lock()
	mode := s.mode
	status := statusFor(mode)
	if readErr != nil { // 본문 수신 도중 끊긴 요청도 기록한다 (spec I7)
		status = http.StatusBadRequest
	}
	s.seq++
	rec := Record{Seq: s.seq, ReceivedAt: s.timeNow().UTC(), Mode: mode.Mode, Status: status, Body: body}
	if readErr != nil {
		rec.Error = readErr.Error()
	}
	line, _ := json.Marshal(rec)
	_, werr := s.writer.Write(append(line, '\n'))
	if werr == nil {
		werr = s.writer.Flush()
	}
	s.stats[status]++
	s.mu.Unlock()
	if werr != nil {
		// 기록 실패는 측정 무효를 뜻하므로 숨기지 않는다.
		log.Printf("RECORD WRITE FAILED seq=%d: %v", rec.Seq, werr)
		http.Error(w, "record write failed", http.StatusInternalServerError)
		return
	}

	if readErr != nil {
		http.Error(w, "read error: "+readErr.Error(), status)
		return
	}
	switch mode.Mode {
	case "latency":
		s.sleep(time.Duration(mode.LatencyMs) * time.Millisecond)
	case "blackhole":
		s.sleep(30 * time.Second)
	case "429":
		w.Header().Set("Retry-After", "1")
	}
	w.WriteHeader(status)
	// 실제 Slack 웹훅은 성공 시 본문 "ok"를 돌려준다 (Alertmanager는 본문이 ok가 아니면 실패로 처리).
	if status == http.StatusOK {
		_, _ = w.Write([]byte("ok"))
	} else {
		_, _ = w.Write([]byte(http.StatusText(status)))
	}
}

func (s *Server) setMode(w http.ResponseWriter, r *http.Request) {
	var m Mode
	if err := json.NewDecoder(r.Body).Decode(&m); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	switch m.Mode {
	case "ok", "5xx", "429", "latency", "blackhole":
	default:
		http.Error(w, "unknown mode "+m.Mode, http.StatusBadRequest)
		return
	}
	s.mu.Lock()
	s.mode = m
	s.mu.Unlock()
	log.Printf("mode -> %s latencyMs=%d", m.Mode, m.LatencyMs)
	writeJSON(w, m)
}

func (s *Server) records(w http.ResponseWriter, _ *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err := s.writer.Flush(); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	f, err := os.Open(s.path)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/x-ndjson")
	_, _ = io.Copy(w, f)
}

func (s *Server) reset(w http.ResponseWriter, _ *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	_ = s.file.Close()
	if err := s.open(os.O_CREATE | os.O_TRUNC | os.O_WRONLY); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	s.seq = 0
	s.stats = map[int]int64{}
	s.mode = Mode{Mode: "ok"}
	writeJSON(w, map[string]string{"reset": "ok"})
}

func (s *Server) statsHandler(w http.ResponseWriter, _ *http.Request) {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := map[string]any{"mode": s.mode, "total": s.seq}
	byStatus := map[string]int64{}
	for k, v := range s.stats {
		byStatus[strconv.Itoa(k)] = v
	}
	out["byStatus"] = byStatus
	writeJSON(w, out)
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /webhook", s.webhook)
	mux.HandleFunc("POST /_control/mode", s.setMode)
	mux.HandleFunc("GET /_control/records", s.records)
	mux.HandleFunc("POST /_control/reset", s.reset)
	mux.HandleFunc("GET /_control/stats", s.statsHandler)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte("ok")) })
	return mux
}

func main() {
	path := envOr("RECORDS_PATH", "/data/records.jsonl")
	addr := envOr("ADDR", ":8070")
	s, err := NewServer(path)
	if err != nil {
		log.Fatalf("open records: %v", err)
	}
	log.Printf("mock-slack listening on %s, records=%s", addr, path)
	log.Fatal(http.ListenAndServe(addr, s.Handler()))
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}
