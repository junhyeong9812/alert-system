#!/usr/bin/env python3
"""실험 실행 디렉터리(experiments/runs/*)를 시나리오별로 묶어 중앙값·반복별 값을 마크다운으로 낸다.

  python3 scripts/summarize-runs.py [--since YYYYMMDDTHHMMSS] > summary.md
"""
import argparse
import collections
import datetime as dt
import glob
import json
import os
import re
import statistics

RUN_RE = re.compile(r"^(?P<scen>.+)-(?P<ts>\d{8}T\d{6})-r(?P<rep>.+)$")


def parse_ts(s):
    return dt.datetime.fromisoformat(s.replace("Z", "+00:00"))


def counter_deltas(path):
    out = {}
    text = open(path).read() if os.path.exists(path) else ""
    if "## counter deltas" not in text:
        return out
    block = text.split("## counter deltas", 1)[1].split("\n## ", 1)[0]
    for line in block.splitlines()[1:]:
        parts = line.split(" ")
        if len(parts) == 3:
            out[f"{parts[0]} {parts[1]}"] = float(parts[2])
    return out


def section(path, title):
    text = open(path).read() if os.path.exists(path) else ""
    if f"## {title}" not in text:
        return []
    # 제목 행의 나머지(빈 문자열)를 버리고 다음 섹션 전까지의 데이터 행만
    lines = text.split(f"## {title}", 1)[1].split("\n## ", 1)[0].splitlines()[1:]
    return [l for l in lines if l.strip()]


def channel_timeline(run, bucket_s=30):
    """도메인 알림 채널 시도(수락·거절)를 실행 시작 기준 bucket 초 단위로 센다 — 재시도 증폭(H2) 판정용."""
    events = [json.loads(l) for l in open(os.path.join(run, "events.jsonl")) if l.strip()]
    start = next(parse_ts(e["t"]) for e in events if e["event"] == "run:start")
    buckets = collections.defaultdict(lambda: [0, 0])
    for line in open(os.path.join(run, "records.jsonl")):
        r = json.loads(line)
        body = r["body"] if isinstance(r["body"], dict) else {}
        if "metadata" not in body:
            continue
        b = int((parse_ts(r["receivedAt"]) - start).total_seconds() // bucket_s) * bucket_s
        buckets[b][0 if r["status"] == 200 else 1] += 1
    return dict(sorted(buckets.items()))


def med(values):
    vals = [v for v in values if v is not None]
    return statistics.median(vals) if vals else None


def fmt(v, unit=""):
    if v is None:
        return "-"
    if isinstance(v, float) and not v.is_integer():
        return f"{v:.1f}{unit}"
    return f"{int(v)}{unit}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--since", default="")
    ap.add_argument("--runs", default="experiments/runs")
    args = ap.parse_args()

    groups = collections.defaultdict(list)
    for d in sorted(glob.glob(os.path.join(args.runs, "*"))):
        m = RUN_RE.match(os.path.basename(d))
        if not m or m["ts"] < args.since or not os.path.exists(os.path.join(d, "report.json")):
            continue
        groups[m["scen"]].append((m["rep"], d))

    for scen, runs in sorted(groups.items()):
        print(f"## {scen}\n")
        rows = []
        for rep, d in runs:
            r = json.load(open(os.path.join(d, "report.json")))
            a, f = r["alerts"], r["flow"]
            det = [x.get("seconds") for x in (r.get("detections") or [])]
            rows.append({
                "rep": rep, "dir": os.path.basename(d),
                "req": f["requests"], "unexp_err": f["unexpectedErrors"],
                "p50": f["latencyMs"]["p50"], "p95": f["latencyMs"]["p95"], "p99": f["latencyMs"]["p99"],
                "expected": a["expected"], "unreached": a.get("unreached", 0), "unknown": a.get("unknown", 0),
                "unknown_seen": a.get("unknownDelivered", 0), "delivered": a["delivered"], "lost": a["lost"],
                "dup": a["duplicates"], "unexpected": a["unexpected"], "volume": a["volume"], "rejected": a["rejected"],
                "e2e_p50": a["e2eMs"]["p50"], "e2e_p95": a["e2eMs"]["p95"], "e2e_max": a["e2eMs"]["max"],
                "detect_s": det[0] if det else None, "ops": len(r.get("opsAlerts") or []),
                "sink_err": r.get("sinkReadErrors", 0),
                "stages": r.get("flowStages") or {},
                "counters": counter_deltas(os.path.join(d, "db-state.txt")),
                "gap": section(os.path.join(d, "db-state.txt"), "dual-write gap (failure records | alert_status rows)"),
                "inbox": section(os.path.join(d, "db-state.txt"), "notification_db inbox (status | count | fail | sent | received | retry_cmd)"),
                "producer": {db: section(os.path.join(d, "db-state.txt"), f"{db}_db alert_status") for db in ("order", "product", "shipping")},
            })
        cols = [("req", "요청"), ("unexp_err", "의도치 않은 실패"), ("p50", "p50 ms"), ("p95", "p95 ms"), ("p99", "p99 ms"),
                ("expected", "정답"), ("unreached", "미도달"), ("unknown", "불명"), ("delivered", "도달"), ("lost", "유실"),
                ("dup", "중복"), ("unexpected", "예상 밖"), ("volume", "사람이 본 알림"), ("rejected", "채널 거절"),
                ("e2e_p50", "e2e p50 ms"), ("e2e_p95", "e2e p95 ms"), ("e2e_max", "e2e max ms"), ("detect_s", "감지 s"),
                ("ops", "운영 알림"), ("sink_err", "sink 오류")]
        print("| 지표 | 중앙값 | " + " | ".join(f"r{x['rep']}" for x in rows) + " |")
        print("|---|---|" + "---|" * len(rows))
        for key, label in cols:
            vals = [x[key] for x in rows]
            print(f"| {label} | {fmt(med(vals))} | " + " | ".join(fmt(v) for v in vals) + " |")
        print()

        stage_names = sorted({s for x in rows for s in x["stages"]})
        if len(stage_names) > 1:
            print("| 구간 | p50 ms (중앙값) | p95 ms (중앙값) | p99 ms (중앙값) | 의도치 않은 실패 (중앙값) |")
            print("|---|---|---|---|---|")
            for s in stage_names:
                st = [x["stages"].get(s) for x in rows if s in x["stages"]]
                print(f"| {s} | {fmt(med([v['latencyMs']['p50'] for v in st]))} | {fmt(med([v['latencyMs']['p95'] for v in st]))} | "
                      f"{fmt(med([v['latencyMs']['p99'] for v in st]))} | {fmt(med([v['unexpectedErrors'] for v in st]))} |")
            print()

        keys = sorted({k for x in rows for k in x["counters"]})
        if keys:
            print("| 카운터 증분 (port metric) | 중앙값 | " + " | ".join(f"r{x['rep']}" for x in rows) + " |")
            print("|---|---|" + "---|" * len(rows))
            for k in keys:
                vals = [x["counters"].get(k, 0.0) for x in rows]
                print(f"| `{k}` | {fmt(med(vals))} | " + " | ".join(fmt(v) for v in vals) + " |")
            print()

        for x in rows:
            print(f"- r{x['rep']} `{x['dir']}` · dual-write(실패기록|alert_status): {', '.join(x['gap'])} · "
                  f"inbox: {'; '.join(x['inbox'])} · 생산자 상태: "
                  + "; ".join(f"{db}=[{', '.join(v)}]" for db, v in x["producer"].items()))
        print()

        if scen == "k6-fi09":
            print("채널 시도 시계열 (실행 시작 기준 30초 버킷, 수락/거절):\n")
            for rep, d in runs:
                tl = channel_timeline(d)
                print(f"- r{rep}: " + " · ".join(f"{b}s {ok}/{bad}" for b, (ok, bad) in tl.items()))
            print()


if __name__ == "__main__":
    main()
