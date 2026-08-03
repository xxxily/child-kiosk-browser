#!/usr/bin/env python3
"""Summarize JSONL emitted by the WebView continuity PoC."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


def max_gap(values: list[int]) -> int | None:
    if len(values) < 2:
        return None
    return max(second - first for first, second in zip(values, values[1:]))


def counter_transitions(
    reports: list[tuple[dict, dict]],
    field: str,
) -> list[dict[str, int]]:
    transitions: list[dict[str, int]] = []
    previous: int | None = None
    for record, payload in reports:
        value = payload.get(field)
        if not isinstance(value, int):
            continue
        if previous is None:
            previous = value
            continue
        if value != previous:
            transitions.append(
                {
                    "wallMs": int(record["wallMs"]),
                    "from": previous,
                    "to": value,
                    "delta": value - previous,
                }
            )
            previous = value
    return transitions


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("path", nargs="?", help="JSONL path; omit to read stdin")
    args = parser.parse_args()
    raw = Path(args.path).read_text() if args.path else sys.stdin.read()
    records = [json.loads(line) for line in raw.splitlines() if line.strip()]
    if not records:
        raise SystemExit("No continuity records found")

    js_reports: list[tuple[dict, dict]] = []
    native = []
    activities = []
    for record in records:
        if record.get("event") == "native_heartbeat":
            native.append(record)
        elif record.get("event") == "js_report":
            try:
                payload = json.loads(record.get("payload", ""))
            except json.JSONDecodeError:
                continue
            js_reports.append((record, payload))
        elif str(record.get("event", "")).startswith("activity_"):
            activities.append(record)

    by_type: dict[str, list[tuple[dict, dict]]] = {}
    for item in js_reports:
        by_type.setdefault(str(item[1].get("type")), []).append(item)

    latest_payload = js_reports[-1][1] if js_reports else {}
    main_times = [item[0]["wallMs"] for item in by_type.get("main_heartbeat", [])]
    worker_times = [item[0]["wallMs"] for item in by_type.get("worker_heartbeat", [])]
    fetch_success_times = [item[0]["wallMs"] for item in by_type.get("fetch_success", [])]
    fetch_failure_times = [item[0]["wallMs"] for item in by_type.get("fetch_failure", [])]
    native_times = [item["wallMs"] for item in native]
    lifecycle_types = {
        "visibilitychange",
        "freeze",
        "resume",
        "pageshow",
        "pagehide",
        "focus",
        "blur",
    }
    lifecycle = [
        {
            "type": payload.get("type"),
            "wallMs": int(record.get("wallMs")),
            "hidden": payload.get("hidden"),
            "visibilityState": payload.get("visibilityState"),
        }
        for record, payload in js_reports
        if payload.get("type") in lifecycle_types
    ]
    freeze_times = [item["wallMs"] for item in lifecycle if item["type"] == "freeze"]
    resume_times = [item["wallMs"] for item in lifecycle if item["type"] == "resume"]
    first_freeze = freeze_times[0] if freeze_times else None
    hidden_at = next(
        (
            item["wallMs"]
            for item in lifecycle
            if item["type"] == "visibilitychange"
            and item.get("hidden") is True
            and first_freeze is not None
            and item["wallMs"] <= first_freeze
        ),
        None,
    )
    latest_resume = resume_times[-1] if resume_times else None
    main_transitions = counter_transitions(js_reports, "mainCount")
    worker_transitions = counter_transitions(js_reports, "workerCount")

    summary = {
        "session": records[0].get("session"),
        "recordCount": len(records),
        "durationMs": records[-1]["wallMs"] - records[0]["wallMs"],
        "firstWallMs": records[0]["wallMs"],
        "lastWallMs": records[-1]["wallMs"],
        "native": {
            "reports": len(native),
            "maxReportGapMs": max_gap(native_times),
            "lastWallMs": native_times[-1] if native_times else None,
            "lastInteractive": native[-1].get("interactive") if native else None,
            "allWakeLocksHeld": all(item.get("wakeLockHeld") for item in native),
            "allWifiLocksHeld": all(item.get("wifiLockHeld") for item in native),
            "firstWakeLockLostWallMs": next(
                (item.get("wallMs") for item in native if not item.get("wakeLockHeld")),
                None,
            ),
        },
        "main": {
            "reports": len(main_times),
            "maxReportGapMs": max_gap(main_times),
            "lastWallMs": main_times[-1] if main_times else None,
            "lastCount": latest_payload.get("mainCount"),
            "lastTimerWallMs": latest_payload.get("lastMainWallMs"),
            "recentCountTransitions": main_transitions[-12:],
        },
        "worker": {
            "reports": len(worker_times),
            "maxReportGapMs": max_gap(worker_times),
            "lastWallMs": worker_times[-1] if worker_times else None,
            "lastCount": latest_payload.get("workerCount"),
            "lastTimerWallMs": latest_payload.get("lastWorkerWallMs"),
            "recentCountTransitions": worker_transitions[-12:],
        },
        "network": {
            "successReports": len(by_type.get("fetch_success", [])),
            "failureReports": len(by_type.get("fetch_failure", [])),
            "lastSuccessCount": latest_payload.get("fetchSuccess"),
            "lastFailureCount": latest_payload.get("fetchFailure"),
            "maxSuccessGapMs": max_gap(fetch_success_times),
            "recentSuccessWallMs": fetch_success_times[-12:],
            "recentFailureWallMs": fetch_failure_times[-12:],
        },
        "page": {
            "visibilityState": latest_payload.get("visibilityState"),
            "hidden": latest_payload.get("hidden"),
            "loadIds": sorted({payload.get("loadId") for _, payload in js_reports if payload.get("loadId")}),
            "loadCounts": sorted({payload.get("loadCount") for _, payload in js_reports if payload.get("loadCount")}),
            "lifecycle": lifecycle,
            "hiddenToFirstFreezeMs": (
                freeze_times[0] - hidden_at if hidden_at is not None and freeze_times else None
            ),
            "freezeCount": len(freeze_times),
            "resumeCount": len(resume_times),
            "postResumeFreezeCount": (
                sum(1 for value in freeze_times if value > latest_resume)
                if latest_resume is not None
                else None
            ),
            "observedAfterLatestResumeMs": (
                records[-1]["wallMs"] - latest_resume if latest_resume is not None else None
            ),
        },
        "activity": [
            {"event": item.get("event"), "wallMs": item.get("wallMs")}
            for item in activities
        ],
        "jsSilenceAtEndMs": (
            records[-1]["wallMs"] - js_reports[-1][0]["wallMs"] if js_reports else None
        ),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
