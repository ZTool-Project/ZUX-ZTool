"""Offline multi-source correlator and diagnostic Markdown/JSON report generator."""

from __future__ import annotations

import json
import math
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Optional

from perfetto.trace_processor import TraceProcessor

from .auditors import detect_periodic_logcat_spinners, parse_logcat_monotonic

FTRACE_LINE_RE = re.compile(
    r"^\s*(.+?)-(\d+)\s+\[(\d+)\]\s+\S+\s+(\d+\.\d+):\s+([a-zA-Z0-9_]+):\s*(.*)$"
)
KV_INT_RE = re.compile(r"(\w+)=(-?\d+)")
KV_HEX_RE = re.compile(r"(\w+)=(0x[0-9a-fA-F]+|[0-9a-fA-F]+)")
COMM_RE = re.compile(r"comm=([^\s]+)")
RESOURCEQS_RE = re.compile(r"optimization(?:\s+here)?\s*\[(\d+),\s*(\d+)\]")
UTIL_NODE_RE = re.compile(r"node\s+(/\S+)\s+with\s+value\s+(\S+)")


def _percentile(sorted_vals: list[float], pct: float) -> float:
    if not sorted_vals:
        return 0.0
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    k = (len(sorted_vals) - 1) * (pct / 100.0)
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_vals[int(k)]
    return sorted_vals[f] * (c - k) + sorted_vals[c] * (k - f)


def parse_gfxinfo_framestats(text: str) -> dict[str, Any]:
    """Parse dumpsys gfxinfo framestats summary, Skia GPU memory, and PROFILEDATA stages."""
    summary: dict[str, Any] = {}
    patterns = {
        "total_frames": (r"Total frames rendered:\s*(\d+)", int),
        "janky_frames": (r"Janky frames:\s*(\d+)\s*\(([\d.]+)%\)", None),
        "janky_frames_legacy": (r"Janky frames \(legacy\):\s*(\d+)\s*\(([\d.]+)%\)", None),
        "p50_ms": (r"50th percentile:\s*(\d+)ms", int),
        "p90_ms": (r"90th percentile:\s*(\d+)ms", int),
        "p95_ms": (r"95th percentile:\s*(\d+)ms", int),
        "p99_ms": (r"99th percentile:\s*(\d+)ms", int),
        "missed_vsync": (r"Number Missed Vsync:\s*(\d+)", int),
        "high_input_latency": (r"Number High input latency:\s*(\d+)", int),
        "slow_ui_thread": (r"Number Slow UI thread:\s*(\d+)", int),
        "slow_bitmap_uploads": (r"Number Slow bitmap uploads:\s*(\d+)", int),
        "slow_issue_draw": (r"Number Slow issue draw commands:\s*(\d+)", int),
        "deadline_missed": (r"Number Frame deadline missed:\s*(\d+)", int),
        "gpu_p50_ms": (r"50th gpu percentile:\s*(\d+)ms", int),
        "gpu_p90_ms": (r"90th gpu percentile:\s*(\d+)ms", int),
        "gpu_p95_ms": (r"95th gpu percentile:\s*(\d+)ms", int),
        "gpu_p99_ms": (r"99th gpu percentile:\s*(\d+)ms", int),
    }
    for key, (pat, caster) in patterns.items():
        m = re.search(pat, text)
        if not m:
            continue
        if caster is None:
            summary[key] = int(m.group(1))
            summary[f"{key}_pct"] = float(m.group(2))
        else:
            summary[key] = caster(m.group(1))

    scratch_m = re.search(
        r"Scratch:\s*(?:Buffer Object:[^\n]*\n\s*)?Surface:\s*([\d.]+)\s*(MB|KB|B)\s*\((\d+)\s*entries\)",
        text,
    )
    if scratch_m:
        val = float(scratch_m.group(1))
        unit = scratch_m.group(2)
        mb = val if unit == "MB" else (val / 1024.0 if unit == "KB" else val / (1024.0 * 1024.0))
        summary["skia_scratch_surface_mb"] = round(mb, 2)
        summary["skia_scratch_surface_entries"] = int(scratch_m.group(3))

    total_gpu_m = re.search(r"Total GPU memory usage:\s*\d+\s*bytes,\s*([\d.]+)\s*(MB|KB)", text)
    if total_gpu_m:
        val = float(total_gpu_m.group(1))
        summary["skia_total_gpu_mb"] = round(val if total_gpu_m.group(2) == "MB" else val / 1024.0, 2)

    # Parse PROFILEDATA CSV block.
    stage_samples: dict[str, list[float]] = defaultdict(list)
    in_profile = False
    headers: list[str] = []
    valid_rows = 0
    for raw in text.splitlines():
        line = raw.strip()
        if line == "---PROFILEDATA---":
            in_profile = not in_profile
            headers = []
            continue
        if not in_profile or not line:
            continue
        cols = [c.strip() for c in line.rstrip(",").split(",")]
        if not headers:
            if "Flags" in cols and "IntendedVsync" in cols:
                headers = cols
            continue
        if len(cols) < len(headers):
            continue
        try:
            row = {h: int(cols[i]) for i, h in enumerate(headers)}
        except ValueError:
            continue
        if row.get("Flags", 1) != 0:
            continue
        if row.get("FrameCompleted", 0) <= 0 or row.get("IntendedVsync", 0) <= 0:
            continue
        if row.get("FrameCompleted", 0) > 10**18:
            continue
        valid_rows += 1

        def _delta(a: str, b: str) -> Optional[float]:
            va, vb = row.get(a), row.get(b)
            if va is None or vb is None or va <= 0 or vb <= 0 or vb < va:
                return None
            return (vb - va) / 1e6

        pairs = [
            ("input_ms", "HandleInputStart", "AnimationStart"),
            ("animation_ms", "AnimationStart", "PerformTraversalsStart"),
            ("measure_layout_ms", "PerformTraversalsStart", "DrawStart"),
            ("draw_ms", "DrawStart", "SyncQueued"),
            ("sync_ms", "SyncStart", "IssueDrawCommandsStart"),
            ("issue_draw_ms", "IssueDrawCommandsStart", "SwapBuffers"),
            ("swap_buffers_ms", "SwapBuffers", "FrameCompleted"),
            ("total_cpu_ms", "IntendedVsync", "FrameCompleted"),
        ]
        for sname, start_k, end_k in pairs:
            d = _delta(start_k, end_k)
            if d is not None:
                stage_samples[sname].append(d)
        if "GpuCompleted" in row and "SwapBuffers" in row:
            gd = _delta("SwapBuffers", "GpuCompleted")
            if gd is not None and gd < 1000.0:
                stage_samples["gpu_ms"].append(gd)

    stages: dict[str, dict[str, float]] = {}
    for sname, vals in stage_samples.items():
        svals = sorted(vals)
        stages[sname] = {
            "mean": round(sum(svals) / len(svals), 2),
            "p95": round(_percentile(svals, 95.0), 2),
            "max": round(svals[-1], 2),
        }

    return {
        "summary": summary,
        "profiledata_valid_frames": valid_rows,
        "stages_ms": stages,
    }


def parse_root_ftrace(
    text: str,
    hwmax_by_cpu: Optional[dict[int, int]] = None,
) -> dict[str, Any]:
    """Parse root ftrace instance output (waltgov_next_freq, cpu_frequency_limits, schedux, etc.)."""
    waltgov_by_cpu: dict[int, dict[str, Any]] = defaultdict(
        lambda: {
            "samples": 0,
            "capped_samples": 0,
            "hwmax_saturated_samples": 0,
            "thermal_isolated_samples": 0,
            "raw_freqs": [],
            "freqs": [],
            "max_caps": [],
        }
    )
    waltgov_cap_events: list[dict[str, Any]] = []
    freq_limit_events: list[dict[str, Any]] = []
    ux_events_by_comm: Counter[str] = Counter()
    ux_events: list[dict[str, Any]] = []
    other_event_counts: Counter[str] = Counter()
    special_events: list[dict[str, Any]] = []

    for raw in text.splitlines():
        if not raw or raw.startswith("#"):
            continue
        m = FTRACE_LINE_RE.match(raw)
        if not m:
            continue
        task_name = m.group(1).strip()
        task_pid = int(m.group(2))
        ftrace_cpu = int(m.group(3))
        ts_s = float(m.group(4))
        event = m.group(5)
        args = m.group(6)

        if event == "waltgov_next_freq":
            kv = {k: int(v) for k, v in KV_INT_RE.findall(args)}
            cpu = kv.get("cpu", ftrace_cpu)
            raw_f = kv.get("raw_freq", 0)
            freq = kv.get("freq", 0)
            p_max = kv.get("policy_max_freq", 0)
            p_min = kv.get("policy_min_freq", 0)
            therm = kv.get("thermal_isolated", 0)
            reason_m = re.search(r"reason=(0x[0-9a-fA-F]+|\d+)", args)
            reason = reason_m.group(1) if reason_m else ""

            st = waltgov_by_cpu[cpu]
            st["samples"] += 1
            st["raw_freqs"].append(raw_f)
            st["freqs"].append(freq)
            st["max_caps"].append(p_max)
            if therm:
                st["thermal_isolated_samples"] += 1
            hwmax = (hwmax_by_cpu or {}).get(cpu, 0)
            if hwmax > 0:
                is_sub_hwmax_cap = 0 < p_max < hwmax and raw_f > p_max
                is_hwmax_sat = p_max >= hwmax and raw_f >= hwmax
            else:
                is_sub_hwmax_cap = p_max > 0 and raw_f > p_max
                is_hwmax_sat = False
            if is_hwmax_sat:
                st["hwmax_saturated_samples"] += 1
            if is_sub_hwmax_cap:
                st["capped_samples"] += 1
                waltgov_cap_events.append(
                    {
                        "ts_s": ts_s,
                        "cpu": cpu,
                        "raw_freq": raw_f,
                        "freq": freq,
                        "policy_min_freq": p_min,
                        "policy_max_freq": p_max,
                        "reason": reason,
                    }
                )
        elif event == "cpu_frequency_limits":
            kv = {k: int(v) for k, v in KV_INT_RE.findall(args)}
            freq_limit_events.append(
                {
                    "ts_s": ts_s,
                    "cpu_id": kv.get("cpu_id", ftrace_cpu),
                    "min_khz": kv.get("min", kv.get("min_freq", 0)),
                    "max_khz": kv.get("max", kv.get("max_freq", 0)),
                    "writer_task": f"{task_name}-{task_pid}",
                }
            )
        elif event in ("dynamic_ux_set", "dynamic_ux_unset"):
            kv = {k: int(v) for k, v in KV_INT_RE.findall(args)}
            cm = COMM_RE.search(args)
            comm = cm.group(1) if cm else task_name
            pid = kv.get("pid", task_pid)
            ux_events_by_comm[comm] += 1
            ux_events.append(
                {
                    "ts_s": ts_s,
                    "event": event,
                    "pid": pid,
                    "comm": comm,
                    "ux_state": kv.get("ux_state", 0),
                    "dynamic_type": kv.get("dynamic_type", kv.get("type", 0)),
                }
            )
        elif event in (
            "sched_set_preferred_cluster",
            "sched_set_boost",
            "sched_pipeline_tasks",
            "core_ctl_set_boost",
            "dcvsh_throttle",
            "cdev_update",
            "thermal_power_cpu_limit",
            "kgsl_clock_throttling",
            "sde_encoder_underrun",
            "selinux_audited",
        ):
            other_event_counts[event] += 1
            if len(special_events) < 200:
                special_events.append(
                    {
                        "ts_s": ts_s,
                        "event": event,
                        "task": f"{task_name}-{task_pid}",
                        "args": args[:160],
                    }
                )
        else:
            other_event_counts[event] += 1

    waltgov_summary: dict[str, Any] = {}
    for cpu, st in sorted(waltgov_by_cpu.items()):
        rf = sorted(st["raw_freqs"])
        af = sorted(st["freqs"])
        mc = sorted(st["max_caps"])
        waltgov_summary[f"cpu{cpu}"] = {
            "samples": st["samples"],
            "capped_samples": st["capped_samples"],
            "capped_pct": round((st["capped_samples"] / st["samples"]) * 100.0, 2) if st["samples"] else 0.0,
            "hwmax_saturated_samples": st["hwmax_saturated_samples"],
            "hwmax_saturated_pct": round((st["hwmax_saturated_samples"] / st["samples"]) * 100.0, 2) if st["samples"] else 0.0,
            "thermal_isolated_samples": st["thermal_isolated_samples"],
            "raw_freq_avg_khz": int(sum(rf) / len(rf)) if rf else 0,
            "raw_freq_p95_khz": int(_percentile([float(x) for x in rf], 95.0)) if rf else 0,
            "freq_avg_khz": int(sum(af) / len(af)) if af else 0,
            "freq_p95_khz": int(_percentile([float(x) for x in af], 95.0)) if af else 0,
            "policy_max_min_seen_khz": mc[0] if mc else 0,
            "policy_max_max_seen_khz": mc[-1] if mc else 0,
        }

    return {
        "waltgov_summary": waltgov_summary,
        "waltgov_cap_events": waltgov_cap_events,
        "cpu_frequency_limits": freq_limit_events,
        "dynamic_ux_by_comm": dict(ux_events_by_comm.most_common(30)),
        "dynamic_ux_events": ux_events,
        "other_event_counts": dict(other_event_counts),
        "special_events_sample": special_events[:50],
    }


def compute_clock_offset_s(
    perfetto_anchors: list[dict[str, Any]],
    capture_meta_anchors: dict[str, Any],
    logcat_entries: list[dict[str, Any]],
) -> tuple[float, str]:
    """Compute (boottime_s - monotonic_s) offset for aligning logcat to Perfetto/ftrace."""
    logcat_by_tag: dict[str, float] = {}
    for e in logcat_entries:
        if e["tag"] == "ZToolPerfAudit" and e["message"] in ("ZTOOL_PERF_SYNC_START", "ZTOOL_PERF_SYNC_END"):
            logcat_by_tag[e["message"]] = e["mono_s"]

    diffs: list[float] = []
    for row in perfetto_anchors:
        msg = str(row.get("msg", "")).strip()
        if msg in logcat_by_tag:
            diffs.append((float(row["ts"]) / 1e9) - logcat_by_tag[msg])
    if diffs:
        return sum(diffs) / len(diffs), "perfetto_android_logs"

    # Fallback: use shell uptime_mid_s collected at exact emit_sync_anchor call.
    fallback_diffs: list[float] = []
    for key in ("sync_start", "sync_end"):
        anc = capture_meta_anchors.get(key)
        if not anc or anc.get("uptime_mid_s") is None:
            continue
        tag = anc.get("tag")
        if tag in logcat_by_tag:
            fallback_diffs.append(float(anc["uptime_mid_s"]) - logcat_by_tag[tag])
    if fallback_diffs:
        return sum(fallback_diffs) / len(fallback_diffs), "proc_uptime_anchor"
    return 0.0, "unaligned_fallback"


def analyze_hal_and_sched_logs(
    logcat_entries: list[dict[str, Any]],
    opcode_map: dict[str, dict[str, Any]],
    boottime_offset_s: float,
) -> dict[str, Any]:
    """Decode Perf HAL [Major, Minor] errors and collect ZUXOS scheduler/HAL log events."""
    hal_errors: list[dict[str, Any]] = []
    decoded_resource_counts: Counter[str] = Counter()
    util_node_counts: Counter[str] = Counter()
    tag_counts: Counter[str] = Counter()

    for e in logcat_entries:
        tag = e["tag"]
        msg = e["message"]
        level = e["level"]
        boottime_s = e["mono_s"] + boottime_offset_s

        is_interesting = (
            "ANDR-PERF" in tag
            or "ZuiHyperScheduler" in tag
            or "ZuiPerformance" in tag
            or "ZuiBoost" in tag
            or "TouchscreenServiceManager" in tag
            or "OvHyperScheduleTool" in tag
            or "PowerHAL" in tag
            or "poweropt" in tag.lower()
            or "hyperschedule" in tag.lower()
            or "vendor.lenovo.hardware" in tag
            or "ThreadDiagnosis" in tag
            or ("auditd" in tag and "avc:" in msg)
        )
        if not is_interesting:
            continue

        tag_counts[f"{level}/{tag}"] += 1
        decoded_node = ""
        if tag == "ANDR-PERF-RESOURCEQS":
            rm = RESOURCEQS_RE.search(msg)
            if rm:
                op_key = f"{int(rm.group(1))},{int(rm.group(2))}"
                node_info = opcode_map.get(op_key)
                decoded_node = node_info["node"] if node_info else f"unknown_opcode_{op_key}"
                decoded_resource_counts[f"[{op_key}] -> {decoded_node}"] += 1
        elif tag == "ANDR-PERF-UTIL":
            um = UTIL_NODE_RE.search(msg)
            if um:
                decoded_node = um.group(1)
                util_node_counts[f"{um.group(1)} = {um.group(2)}"] += 1

        if level in ("E", "W") or "avc:" in msg or "Failed" in msg or "fail" in msg.lower() or "ZuiHyperScheduler" in tag:
            hal_errors.append(
                {
                    "boottime_s": round(boottime_s, 4),
                    "mono_s": e["mono_s"],
                    "pid": e["pid"],
                    "level": level,
                    "tag": tag,
                    "decoded_node": decoded_node,
                    "message": msg[:220],
                }
            )

    return {
        "interesting_tag_counts": dict(tag_counts.most_common(30)),
        "decoded_resourceqs_failures": dict(decoded_resource_counts.most_common(25)),
        "util_node_failures": dict(util_node_counts.most_common(25)),
        "events": hal_errors,
    }


def compute_tis_delta(
    before: dict[str, Any],
    after: dict[str, Any],
) -> dict[str, Any]:
    """Compute time_in_state delta distribution per cluster per thread."""
    out: dict[str, Any] = {}
    for tid_str, a_info in after.items():
        b_info = before.get(tid_str, {"clusters": {}})
        clusters_out: dict[str, Any] = {}
        for cname, a_freqs in a_info.get("clusters", {}).items():
            b_freqs = b_info.get("clusters", {}).get(cname, {})
            deltas: dict[int, int] = {}
            total = 0
            for freq, ticks in a_freqs.items():
                dt = ticks - b_freqs.get(freq, 0)
                if dt > 0:
                    deltas[freq] = dt
                    total += dt
            if total > 0:
                top_freqs = sorted(deltas.items(), key=lambda kv: -kv[1])[:5]
                clusters_out[cname] = {
                    "total_ticks": total,
                    "top_frequencies": [
                        {
                            "khz": freq,
                            "ticks": dt,
                            "pct": round((dt / total) * 100.0, 1),
                        }
                        for freq, dt in top_freqs
                    ],
                }
        if clusters_out:
            out[tid_str] = {
                "comm": a_info.get("comm", ""),
                "clusters": clusters_out,
            }
    return out


def _cluster_name_for_cpu(cpu: int, cpu_capacities: dict[str, int]) -> str:
    if not cpu_capacities:
        return f"cpu{cpu}"
    caps = sorted(set(cpu_capacities.values()))
    c_cap = cpu_capacities.get(f"cpu{cpu}")
    if c_cap is None:
        return f"cpu{cpu}"
    if c_cap == caps[0]:
        return "Little"
    if c_cap == caps[-1] and len(caps) >= 2:
        return "Prime"
    return "Big/Mid"


def correlate_jank_window(
    frame: dict[str, Any],
    hal_events: list[dict[str, Any]],
    waltgov_caps: list[dict[str, Any]],
    sidecar_ticks: list[dict[str, Any]],
    lookback_s: float = 0.15,
) -> dict[str, Any]:
    """Collect concurrent HAL errors, governor caps, and sidecar state for a slow frame."""
    t_end = frame["end_s"]
    t_start = frame["start_s"] - lookback_s

    near_hal = [
        ev for ev in hal_events
        if t_start <= ev["boottime_s"] <= t_end
    ]
    near_caps = [
        cap for cap in waltgov_caps
        if t_start <= cap["ts_s"] <= t_end
    ]

    nearest_sidecar = None
    best_dist = 9999.0
    for tick in sidecar_ticks:
        dist = abs(tick["uptime_s"] - frame["start_s"])
        if dist < best_dist and dist <= 1.0:
            best_dist = dist
            nearest_sidecar = tick

    hal_summary = Counter()
    for ev in near_hal:
        label = ev["tag"]
        if ev.get("decoded_node"):
            label = f"{label}:{ev['decoded_node']}"
        hal_summary[label] += 1

    return {
        "concurrent_hal_errors": dict(hal_summary.most_common(8)),
        "concurrent_waltgov_cap_count": len(near_caps),
        "sidecar_snapshot": nearest_sidecar,
    }


class PerfAuditAnalyzer:
    """Queries Perfetto TraceProcessor and aligns ftrace, sidecar, logcat, and gfxinfo."""

    def __init__(self, run_dir: Path, package: Optional[str] = None) -> None:
        self.run_dir = Path(run_dir)
        self.meta = self._load_json("capture_meta.json", default={})
        self.env = self._load_json("env_audit.json", default={})
        self.idle = self._load_json("idle_audit.json", default={})
        self.opcode_map = self._load_json("perf_opcode_map.json", default={})
        self.restore_res = self._load_json("restore_result.json", default=None)
        self.package = package or self.meta.get("package") or self.env.get("package") or "com.qimian233.ztool"

    def _load_json(self, name: str, default: Any = None) -> Any:
        p = self.run_dir / name
        if not p.exists():
            return default
        return json.loads(p.read_text(encoding="utf-8"))

    def _load_sidecar(self) -> list[dict[str, Any]]:
        p = self.run_dir / "sched_sidecar.jsonl"
        if not p.exists():
            return []
        ticks: list[dict[str, Any]] = []
        for line in p.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.strip():
                ticks.append(json.loads(line))
        return ticks

    def _analyze_perfetto(self) -> dict[str, Any]:
        trace_path = self.run_dir / "trace.perfetto-trace"
        if not trace_path.exists() or trace_path.stat().st_size == 0:
            return {"error": "trace.perfetto-trace missing or empty"}

        cpu_caps = self.env.get("cpu_capacities", {})
        tp = TraceProcessor(trace=str(trace_path))
        try:
            anchors_rows = [
                {"ts": r.ts, "tag": r.tag, "msg": r.msg}
                for r in tp.query(
                    "SELECT ts, tag, msg FROM android_logs "
                    "WHERE tag = 'ZToolPerfAudit' OR msg LIKE '%ZTOOL_PERF_SYNC_%' "
                    "ORDER BY ts"
                )
            ]

            sync_start_ns = None
            sync_end_ns = None
            for r in anchors_rows:
                msg = str(r["msg"]).strip()
                if msg == "ZTOOL_PERF_SYNC_START" and sync_start_ns is None:
                    sync_start_ns = int(r["ts"])
                elif msg == "ZTOOL_PERF_SYNC_END":
                    sync_end_ns = int(r["ts"])

            proc_rows = list(
                tp.query(
                    f"SELECT upid, pid, name FROM process WHERE name = '{self.package}' ORDER BY upid DESC"
                )
            )
            upids = [int(r.upid) for r in proc_rows]
            pids = [int(r.pid) for r in proc_rows]

            threads_info: list[dict[str, Any]] = []
            ui_utids: list[int] = []
            rt_utids: list[int] = []
            if upids:
                upid_sql = ",".join(str(u) for u in upids)
                for r in tp.query(
                    f"SELECT utid, tid, name, is_main_thread FROM thread WHERE upid IN ({upid_sql})"
                ):
                    t_item = {
                        "utid": int(r.utid),
                        "tid": int(r.tid),
                        "name": r.name or "",
                        "is_main_thread": bool(r.is_main_thread),
                    }
                    threads_info.append(t_item)
                    if r.is_main_thread:
                        ui_utids.append(int(r.utid))
                    if (r.name or "") == "RenderThread":
                        rt_utids.append(int(r.utid))

            # Inspect actual_frame_timeline_slice columns dynamically.
            aft_cols = {r.name for r in tp.query("PRAGMA table_info(actual_frame_timeline_slice)")}
            frames_data: dict[str, Any] = {
                "total_frames": 0,
                "janky_frames": 0,
                "jank_pct": 0.0,
                "jank_types": {},
                "dur_p50_ms": 0.0,
                "dur_p90_ms": 0.0,
                "dur_p95_ms": 0.0,
                "dur_p99_ms": 0.0,
                "dur_max_ms": 0.0,
                "top_janky_frames": [],
            }
            if upids and aft_cols:
                upid_sql = ",".join(str(u) for u in upids)
                win_filter = ""
                if sync_start_ns is not None and sync_end_ns is not None:
                    win_filter = f" AND ts >= {sync_start_ns} AND ts <= {sync_end_ns}"

                sel_cols = ["ts", "dur"]
                for opt in ("jank_type", "present_type", "on_time_finish", "layer_name", "display_frame_token", "surface_frame_token"):
                    if opt in aft_cols:
                        sel_cols.append(opt)
                sql = (
                    f"SELECT {', '.join(sel_cols)} FROM actual_frame_timeline_slice "
                    f"WHERE upid IN ({upid_sql}) AND dur > 0{win_filter} ORDER BY ts"
                )
                frame_rows = list(tp.query(sql))
                all_durs_ms: list[float] = []
                jank_type_counts: Counter[str] = Counter()
                jank_frames: list[dict[str, Any]] = []
                for fr in frame_rows:
                    dur_ms = float(fr.dur) / 1e6
                    all_durs_ms.append(dur_ms)
                    jtype = getattr(fr, "jank_type", None) or "None"
                    ptype = getattr(fr, "present_type", None) or ""
                    on_time = getattr(fr, "on_time_finish", None)
                    is_jank = (
                        (jtype not in ("None", "No Jank", ""))
                        or ("Jank" in str(ptype))
                        or (on_time == 0)
                    )
                    if is_jank:
                        jank_type_counts[str(jtype)] += 1
                    if is_jank or dur_ms > 8.5:
                        jank_frames.append(
                            {
                                "ts_ns": int(fr.ts),
                                "dur_ns": int(fr.dur),
                                "start_s": round(int(fr.ts) / 1e9, 4),
                                "end_s": round((int(fr.ts) + int(fr.dur)) / 1e9, 4),
                                "dur_ms": round(dur_ms, 2),
                                "jank_type": str(jtype),
                                "present_type": str(ptype),
                                "layer": str(getattr(fr, "layer_name", "") or ""),
                            }
                        )

                all_durs_sorted = sorted(all_durs_ms)
                jank_count = sum(jank_type_counts.values())
                total_f = len(all_durs_sorted)
                frames_data["total_frames"] = total_f
                frames_data["janky_frames"] = jank_count
                frames_data["jank_pct"] = round((jank_count / total_f) * 100.0, 2) if total_f else 0.0
                frames_data["jank_types"] = dict(jank_type_counts.most_common())
                if all_durs_sorted:
                    frames_data["dur_p50_ms"] = round(_percentile(all_durs_sorted, 50.0), 2)
                    frames_data["dur_p90_ms"] = round(_percentile(all_durs_sorted, 90.0), 2)
                    frames_data["dur_p95_ms"] = round(_percentile(all_durs_sorted, 95.0), 2)
                    frames_data["dur_p99_ms"] = round(_percentile(all_durs_sorted, 99.0), 2)
                    frames_data["dur_max_ms"] = round(all_durs_sorted[-1], 2)

                jank_frames.sort(key=lambda x: -x["dur_ms"])
                top_frames = jank_frames[:15]

                # Query thread_state breakdown for each of Top-15 slow/janky frames.
                for jf in top_frames:
                    jf["thread_breakdown"] = {}
                    for role_name, utid_list in (("UI", ui_utids), ("RenderThread", rt_utids)):
                        if not utid_list:
                            continue
                        utid_sql = ",".join(str(u) for u in utid_list)
                        f_ts = jf["ts_ns"]
                        f_end = f_ts + jf["dur_ns"]
                        ts_rows = list(
                            tp.query(
                                f"""
                                SELECT state, cpu, blocked_function,
                                       SUM(MIN(ts + dur, {f_end}) - MAX(ts, {f_ts})) AS overlap_ns
                                FROM thread_state
                                WHERE utid IN ({utid_sql})
                                  AND ts < {f_end}
                                  AND (ts + dur) > {f_ts}
                                GROUP BY state, cpu, blocked_function
                                """
                            )
                        )
                        running_by_cluster: Counter[str] = Counter()
                        runnable_ns = 0
                        sleep_ns = 0
                        blocked_funcs: Counter[str] = Counter()
                        for tr in ts_rows:
                            ons = int(tr.overlap_ns or 0)
                            if ons <= 0:
                                continue
                            st_val = str(tr.state)
                            if st_val == "Running":
                                cname = _cluster_name_for_cpu(int(tr.cpu), cpu_caps)
                                running_by_cluster[cname] += ons
                            elif st_val in ("R", "R+"):
                                runnable_ns += ons
                            else:
                                sleep_ns += ons
                                if tr.blocked_function:
                                    blocked_funcs[str(tr.blocked_function)] += ons

                        jf["thread_breakdown"][role_name] = {
                            "running_ms": round(sum(running_by_cluster.values()) / 1e6, 2),
                            "runnable_ms": round(runnable_ns / 1e6, 2),
                            "sleep_ms": round(sleep_ns / 1e6, 2),
                            "running_by_cluster_ms": {
                                k: round(v / 1e6, 2) for k, v in running_by_cluster.items()
                            },
                            "top_blocked_ms": {
                                k: round(v / 1e6, 2) for k, v in blocked_funcs.most_common(3)
                            },
                        }

                frames_data["top_janky_frames"] = top_frames

            # Whole-window thread placement summary for UI & RenderThread.
            window_thread_summary: dict[str, Any] = {}
            if ui_utids or rt_utids:
                win_sql = ""
                if sync_start_ns is not None and sync_end_ns is not None:
                    win_sql = f" AND ts < {sync_end_ns} AND (ts + dur) > {sync_start_ns}"
                for role_name, utid_list in (("UI", ui_utids), ("RenderThread", rt_utids)):
                    if not utid_list:
                        continue
                    utid_sql = ",".join(str(u) for u in utid_list)
                    rows = list(
                        tp.query(
                            f"""
                            SELECT state, cpu, SUM(dur) AS total_ns
                            FROM thread_state
                            WHERE utid IN ({utid_sql}) AND dur > 0{win_sql}
                            GROUP BY state, cpu
                            """
                        )
                    )
                    by_cluster: Counter[str] = Counter()
                    runnable_ns = 0
                    sleep_ns = 0
                    for r in rows:
                        tns = int(r.total_ns or 0)
                        if r.state == "Running":
                            by_cluster[_cluster_name_for_cpu(int(r.cpu), cpu_caps)] += tns
                        elif r.state in ("R", "R+"):
                            runnable_ns += tns
                        else:
                            sleep_ns += tns
                    total_run = sum(by_cluster.values())
                    window_thread_summary[role_name] = {
                        "total_running_ms": round(total_run / 1e6, 2),
                        "total_runnable_ms": round(runnable_ns / 1e6, 2),
                        "total_sleep_ms": round(sleep_ns / 1e6, 2),
                        "cluster_share_pct": {
                            k: round((v / total_run) * 100.0, 1)
                            for k, v in by_cluster.items()
                        } if total_run else {},
                    }

            return {
                "anchors": anchors_rows,
                "sync_window_s": {
                    "start": round(sync_start_ns / 1e9, 4) if sync_start_ns else None,
                    "end": round(sync_end_ns / 1e9, 4) if sync_end_ns else None,
                },
                "pids": pids,
                "threads_count": len(threads_info),
                "frames": frames_data,
                "window_thread_summary": window_thread_summary,
            }
        finally:
            tp.close()

    def _build_hwmax_by_cpu(self) -> dict[int, int]:
        out: dict[int, int] = {}
        for pdata in self.env.get("policies", {}).values():
            hwmax = int(pdata.get("hwmax_khz", 0) or 0)
            if hwmax <= 0:
                continue
            for tok in str(pdata.get("cpus", "")).split():
                if tok.isdigit():
                    out[int(tok)] = hwmax
        return out

    def analyze(self) -> dict[str, Any]:
        perfetto_res = self._analyze_perfetto()
        hwmax_by_cpu = self._build_hwmax_by_cpu()

        ftrace_path = self.run_dir / "root_ftrace.txt"
        ftrace_res = (
            parse_root_ftrace(
                ftrace_path.read_text(encoding="utf-8", errors="replace"),
                hwmax_by_cpu=hwmax_by_cpu,
            )
            if ftrace_path.exists()
            else {}
        )

        gfx_path = self.run_dir / "gfxinfo_framestats.txt"
        gfx_res = (
            parse_gfxinfo_framestats(gfx_path.read_text(encoding="utf-8", errors="replace"))
            if gfx_path.exists()
            else {}
        )

        log_path = self.run_dir / "logcat_monotonic.txt"
        log_lines = (
            log_path.read_text(encoding="utf-8", errors="replace").splitlines()
            if log_path.exists()
            else []
        )
        log_entries = parse_logcat_monotonic(log_lines)
        offset_s, offset_source = compute_clock_offset_s(
            perfetto_res.get("anchors", []),
            self.meta.get("anchors", {}),
            log_entries,
        )
        hal_logs = analyze_hal_and_sched_logs(log_entries, self.opcode_map, offset_s)
        capture_spinners = detect_periodic_logcat_spinners(log_lines)

        sidecar_ticks = self._load_sidecar()
        tis_delta = compute_tis_delta(
            self.meta.get("time_in_state_before", {}),
            self.meta.get("time_in_state_after", {}),
        )

        # Correlate Top-15 janky frames with HAL errors, WALT caps, and sidecar state.
        top_frames = perfetto_res.get("frames", {}).get("top_janky_frames", [])
        for jf in top_frames:
            jf["correlation"] = correlate_jank_window(
                frame=jf,
                hal_events=hal_logs.get("events", []),
                waltgov_caps=ftrace_res.get("waltgov_cap_events", []),
                sidecar_ticks=sidecar_ticks,
            )

        # Summarize sidecar affinity / uclamp / ux_stat coverage during capture.
        sidecar_summary = self._summarize_sidecar(sidecar_ticks)

        # Check whether target package received dynamic_ux_set in root ftrace.
        target_pids = set(perfetto_res.get("pids", []))
        if self.env.get("target_package", {}).get("pid"):
            target_pids.add(int(self.env["target_package"]["pid"]))
        target_ux_events = [
            ev for ev in ftrace_res.get("dynamic_ux_events", [])
            if ev.get("pid") in target_pids or self.package.split(".")[-1] in ev.get("comm", "")
        ]

        verdict = self._build_verdict(
            perfetto_res=perfetto_res,
            gfx_res=gfx_res,
            ftrace_res=ftrace_res,
            hal_logs=hal_logs,
            sidecar_summary=sidecar_summary,
            target_ux_count=len(target_ux_events),
        )

        summary = {
            "package": self.package,
            "mode": self.meta.get("mode") or ("baseline" if not self.restore_res else self.restore_res.get("mode")),
            "clock_alignment": {
                "boottime_minus_monotonic_s": round(offset_s, 6),
                "source": offset_source,
            },
            "verdict": verdict,
            "env_findings": self.env.get("findings", []),
            "perfetto": perfetto_res,
            "gfxinfo": gfx_res,
            "root_ftrace_summary": {
                "waltgov_summary": ftrace_res.get("waltgov_summary", {}),
                "waltgov_cap_event_count": len(ftrace_res.get("waltgov_cap_events", [])),
                "cpu_frequency_limits_count": len(ftrace_res.get("cpu_frequency_limits", [])),
                "cpu_frequency_limits_sample": ftrace_res.get("cpu_frequency_limits", [])[:15],
                "dynamic_ux_by_comm": ftrace_res.get("dynamic_ux_by_comm", {}),
                "target_dynamic_ux_event_count": len(target_ux_events),
                "other_event_counts": ftrace_res.get("other_event_counts", {}),
            },
            "sidecar_summary": sidecar_summary,
            "time_in_state_delta": tis_delta,
            "hal_and_scheduler_logs": {
                "interesting_tag_counts": hal_logs.get("interesting_tag_counts", {}),
                "decoded_resourceqs_failures": hal_logs.get("decoded_resourceqs_failures", {}),
                "util_node_failures": hal_logs.get("util_node_failures", {}),
                "total_flagged_events": len(hal_logs.get("events", [])),
            },
            "idle_audit": self.idle,
            "capture_log_spinners": capture_spinners,
            "restore_verification": self.restore_res,
        }

        (self.run_dir / "summary.json").write_text(
            json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8"
        )
        report_md = self._render_markdown(summary)
        (self.run_dir / "report.md").write_text(report_md, encoding="utf-8")
        return summary

    def _summarize_sidecar(self, ticks: list[dict[str, Any]]) -> dict[str, Any]:
        if not ticks:
            return {"ticks": 0}
        policy_max_observed: dict[str, set[int]] = defaultdict(set)
        thread_masks: dict[str, set[str]] = defaultdict(set)
        thread_uclamp_min: dict[str, set[str]] = defaultdict(set)
        target_in_ux_stat_ticks = 0
        pkg_tail = self.package.split(".")[-1]

        for t in ticks:
            for pname, pvals in t.get("policies", {}).items():
                if "max" in pvals:
                    policy_max_observed[pname].add(pvals["max"])
            for th in t.get("threads", []):
                key = th.get("comm", str(th.get("tid")))
                thread_masks[key].add(th.get("cpus_allowed", ""))
                thread_uclamp_min[key].add(th.get("uclamp_min", ""))
            if any(pkg_tail in line for line in t.get("ux_stat", [])):
                target_in_ux_stat_ticks += 1

        return {
            "ticks": len(ticks),
            "policy_max_observed_khz": {k: sorted(v) for k, v in sorted(policy_max_observed.items())},
            "thread_cpus_allowed": {k: sorted(v) for k, v in thread_masks.items()},
            "thread_uclamp_min": {k: sorted(v) for k, v in thread_uclamp_min.items()},
            "target_in_ux_stat_ticks": target_in_ux_stat_ticks,
        }

    def _build_verdict(
        self,
        perfetto_res: dict[str, Any],
        gfx_res: dict[str, Any],
        ftrace_res: dict[str, Any],
        hal_logs: dict[str, Any],
        sidecar_summary: dict[str, Any],
        target_ux_count: int,
    ) -> list[str]:
        bullets: list[str] = []
        frames = perfetto_res.get("frames", {})
        jank_pct = frames.get("jank_pct", 0.0)
        bullets.append(
            f"FrameTimeline: {frames.get('janky_frames', 0)}/{frames.get('total_frames', 0)} janky frames "
            f"({jank_pct}%), p95={frames.get('dur_p95_ms', 0)}ms, p99={frames.get('dur_p99_ms', 0)}ms, max={frames.get('dur_max_ms', 0)}ms."
        )

        # App UI vs GPU check.
        gs = gfx_res.get("summary", {})
        if gs.get("slow_ui_thread", 0) > 0:
            bullets.append(
                f"Rendering bottleneck leans CPU/UI thread: Slow UI thread={gs.get('slow_ui_thread')} vs GPU p99={gs.get('gpu_p99_ms', '?')}ms; "
                f"Skia Scratch Surface={gs.get('skia_scratch_surface_mb', '?')}MB ({gs.get('skia_scratch_surface_entries', '?')} entries)."
            )

        # Frequency capping check.
        walt = ftrace_res.get("waltgov_summary", {})
        capped_cpus = [
            f"{cpu} ({st['capped_samples']}/{st['samples']}={st['capped_pct']}%)"
            for cpu, st in walt.items()
            if st.get("capped_samples", 0) > 0
        ]
        if capped_cpus:
            bullets.append(
                "Governor frequency-capping active (raw_freq > policy_max_freq): " + ", ".join(capped_cpus)
            )

        # HAL error check.
        res_fails = hal_logs.get("decoded_resourceqs_failures", {})
        if res_fails:
            bullets.append(
                f"Perf HAL errors fired during workload ({sum(res_fails.values())} RESOURCEQS failures): "
                + ", ".join(f"{k} x{v}" for k, v in list(res_fails.items())[:4])
            )

        # ZUXOS UX / thread placement check.
        win_th = perfetto_res.get("window_thread_summary", {})
        ui_share = win_th.get("UI", {}).get("cluster_share_pct", {})
        little_pct = ui_share.get("Little", 0.0)
        if little_pct > 25.0 or (target_ux_count == 0 and sidecar_summary.get("target_in_ux_stat_ticks", 0) == 0):
            bullets.append(
                f"ZUXOS UX/placement: target had dynamic_ux={target_ux_count}, static_ux ticks={sidecar_summary.get('target_in_ux_stat_ticks', 0)}, "
                f"UI thread Little-core share={little_pct}%."
            )

        # Idle spinners.
        spinners = self.idle.get("logcat_analysis", {}).get("periodic_spinners", [])
        if spinners:
            bullets.append(
                "Periodic idle spinners detected: "
                + ", ".join(f"{s['tag']} (every {s['period_mean_s']}s)" for s in spinners[:3])
            )
        return bullets

    def _render_markdown(self, s: dict[str, Any]) -> str:
        lines: list[str] = [
            f"# ZTool + ZUXOS Performance Audit Report ({s['package']})",
            "",
            f"- **Clock alignment (`CLOCK_BOOTTIME - CLOCK_MONOTONIC`)**: `{s['clock_alignment']['boottime_minus_monotonic_s']}s` (`{s['clock_alignment']['source']}`)",
            "",
            "## 1. Executive Verdict",
        ]
        for b in s["verdict"]:
            lines.append(f"- {b}")

        lines.extend(["", "## 2. Environment Hygiene & Confounders (Layer 0)"])
        for f in s.get("env_findings", []):
            lines.append(f"- **[{f['severity']}] {f['title']}**: {f['detail']}")

        lines.extend(["", "## 3. Rendering & FrameTimeline"])
        pf = s["perfetto"].get("frames", {})
        lines.append(
            f"- Perfetto FrameTimeline: `{pf.get('janky_frames', 0)}/{pf.get('total_frames', 0)}` "
            f"({pf.get('jank_pct', 0)}%), `p50={pf.get('dur_p50_ms', 0)}ms`, `p90={pf.get('dur_p90_ms', 0)}ms`, "
            f"`p95={pf.get('dur_p95_ms', 0)}ms`, `p99={pf.get('dur_p99_ms', 0)}ms`, `max={pf.get('dur_max_ms', 0)}ms`"
        )
        lines.append(f"- Jank types: `{pf.get('jank_types', {})}`")
        gs = s.get("gfxinfo", {}).get("summary", {})
        if gs:
            lines.append(
                f"- Gfxinfo: SlowUI=`{gs.get('slow_ui_thread')}`, MissedVsync=`{gs.get('missed_vsync')}`, "
                f"HighInputLat=`{gs.get('high_input_latency')}`, SlowIssueDraw=`{gs.get('slow_issue_draw')}`, "
                f"GPU p99=`{gs.get('gpu_p99_ms')}ms`, Skia Scratch Surface=`{gs.get('skia_scratch_surface_mb')}MB` (`{gs.get('skia_scratch_surface_entries')}` entries)"
            )
        stages = s.get("gfxinfo", {}).get("stages_ms", {})
        if stages:
            lines.append("")
            lines.append("| Frame Stage | Mean (ms) | p95 (ms) | Max (ms) |")
            lines.append("|---|---:|---:|---:|")
            for st_name, vals in stages.items():
                lines.append(f"| `{st_name}` | {vals['mean']} | {vals['p95']} | {vals['max']} |")

        lines.extend(["", "## 4. CPU Frequency Scaling & Capping (`waltgov_next_freq` + `time_in_state`)"])
        walt = s["root_ftrace_summary"].get("waltgov_summary", {})
        if walt:
            lines.append("| CPU | Samples | Sub-HWMax Capped | HW-Max Saturated | Raw Avg/p95 (kHz) | Applied Avg/p95 (kHz) | Observed `policy_max` (kHz) |")
            lines.append("|---|---:|---:|---:|---:|---:|---:|")
            for cpu, st in walt.items():
                lines.append(
                    f"| `{cpu}` | {st['samples']} | {st['capped_samples']} ({st['capped_pct']}%) | "
                    f"{st.get('hwmax_saturated_samples', 0)} ({st.get('hwmax_saturated_pct', 0.0)}%) | "
                    f"{st['raw_freq_avg_khz']} / {st['raw_freq_p95_khz']} | "
                    f"{st['freq_avg_khz']} / {st['freq_p95_khz']} | "
                    f"{st['policy_max_min_seen_khz']}..{st['policy_max_max_seen_khz']} |"
                )
        tis = s.get("time_in_state_delta", {})
        if tis:
            lines.append("")
            lines.append("**Thread `time_in_state` delta (top 3 frequencies per cluster):**")
            for tid_str, tinfo in tis.items():
                parts = []
                for cname, cdata in sorted(tinfo.get("clusters", {}).items()):
                    top_str = ", ".join(
                        f"{f['khz']}kHz({f['pct']}%)" for f in cdata.get("top_frequencies", [])[:3]
                    )
                    parts.append(f"{cname}[{cdata.get('total_ticks', 0)}t]: {top_str}")
                if parts:
                    lines.append(f"- TID `{tid_str}` (`{tinfo.get('comm', '')}`): " + "; ".join(parts))

        lines.extend(["", "## 5. Thread Placement, Affinity, uclamp & ZUXOS UX"])
        for role, wst in s["perfetto"].get("window_thread_summary", {}).items():
            lines.append(
                f"- **{role}**: running=`{wst['total_running_ms']}ms`, runnable_wait=`{wst['total_runnable_ms']}ms`, "
                f"sleep=`{wst['total_sleep_ms']}ms`, cluster_share=`{wst['cluster_share_pct']}`"
            )
        ss = s.get("sidecar_summary", {})
        lines.append(f"- Sidecar `Cpus_allowed_list`: `{ss.get('thread_cpus_allowed', {})}`")
        lines.append(f"- Sidecar `uclamp.min`: `{ss.get('thread_uclamp_min', {})}`")
        lines.append(
            f"- Lenovo HyperSchedule dynamic_ux events (top comms): `{s['root_ftrace_summary'].get('dynamic_ux_by_comm', {})}`; "
            f"target dynamic_ux count=`{s['root_ftrace_summary'].get('target_dynamic_ux_event_count', 0)}`, "
            f"static_ux ticks=`{ss.get('target_in_ux_stat_ticks', 0)}`"
        )

        lines.extend(["", "## 6. Scheduler-Induced HAL & Kernel Errors"])
        res_f = s["hal_and_scheduler_logs"].get("decoded_resourceqs_failures", {})
        util_f = s["hal_and_scheduler_logs"].get("util_node_failures", {})
        lines.append(f"- Decoded `ANDR-PERF-RESOURCEQS` failures: `{res_f}`")
        lines.append(f"- Decoded `ANDR-PERF-UTIL` failures: `{util_f}`")
        lines.append(f"- Notable scheduler/HAL log tag counts: `{s['hal_and_scheduler_logs'].get('interesting_tag_counts', {})}`")

        lines.extend(["", "## 7. Top Slow/Janky Frames — Cross-Layer Correlation"])
        top_f = pf.get("top_janky_frames", [])
        if top_f:
            lines.append("| # | Start (s) | Dur (ms) | Jank Type | UI Run/Wait/Cluster | RT Run/Wait/Cluster | Concurrent HAL Errors | WALT Caps |")
            lines.append("|---:|---:|---:|---|---|---|---|---|")
            for idx, jf in enumerate(top_f, 1):
                ui = jf.get("thread_breakdown", {}).get("UI", {})
                rt = jf.get("thread_breakdown", {}).get("RenderThread", {})
                corr = jf.get("correlation", {})
                lines.append(
                    f"| {idx} | {jf['start_s']} | {jf['dur_ms']} | `{jf['jank_type']}` | "
                    f"{ui.get('running_ms', 0)}/{ui.get('runnable_ms', 0)} {ui.get('running_by_cluster_ms', {})} | "
                    f"{rt.get('running_ms', 0)}/{rt.get('runnable_ms', 0)} {rt.get('running_by_cluster_ms', {})} | "
                    f"`{corr.get('concurrent_hal_errors', {})}` | {corr.get('concurrent_waltgov_cap_count', 0)} |"
                )

        lines.extend(["", "## 8. Idle-Spinning & Noisy System Services (Layer 4)"])
        idle = s.get("idle_audit", {})
        if idle:
            lines.append(f"- Idle window: `{idle.get('elapsed_s', 0)}s`, sampled threads: `{idle.get('total_threads_sampled', 0)}`")
            for th in idle.get("top_cpu_threads", [])[:10]:
                lines.append(
                    f"  - CPU: PID `{th['pid']}` TID `{th['tid']}` `{th['comm']}` (`{th['proc_name']}`) -> "
                    f"`{th['cpu_ms']}ms` (`{th['cpu_pct']}%`), `{th['slices_per_s']} slices/s`"
                )
            for sp in idle.get("logcat_analysis", {}).get("periodic_spinners", []):
                lines.append(
                    f"  - Periodic spinner: PID `{sp['pid']}` `{sp['tag']}` x{sp['count']} "
                    f"(every `{sp['period_mean_s']}s`, cv=`{sp['cv']}`): `{sp['template']}`"
                )

        if s.get("restore_verification"):
            rv = s["restore_verification"]
            lines.extend(["", "## 9. Device State Restore Verification"])
            lines.append(
                f"- Mode `{rv.get('mode')}`: **{'PASS' if rv.get('ok') else 'FAIL'}** "
                f"(checked `{rv.get('checked_count')}` nodes/states, mismatches=`{len(rv.get('mismatches', []))}`)"
            )
            for mm in rv.get("mismatches", []):
                lines.append(f"  - MISMATCH `{mm['target']}.{mm['field']}`: expected `{mm['expected']}`, actual `{mm['actual']}`")
            for note in rv.get("notes", []):
                lines.append(f"  - Note: {note}")

        return "\n".join(lines) + "\n"
