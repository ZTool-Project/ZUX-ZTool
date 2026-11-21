"""Layer 0 (Environment Hygiene) and Layer 4 (Idle-Spin & Contention) auditors."""

from __future__ import annotations

import json
import math
import re
import shlex
import time
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path
from typing import Any, Optional

from .adb_utils import AdbClient

LOGCAT_MONOTONIC_RE = re.compile(
    r"^\s*(\d+\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEFA])\s+([^:]+?)\s*:\s*(.*)$"
)
_HEX_RE = re.compile(r"\b0x[0-9a-fA-F]+\b")
_NUM_RE = re.compile(r"\b\d+(?:\.\d+)?\b")


def normalize_log_message(msg: str) -> str:
    msg = _HEX_RE.sub("<HEX>", msg)
    msg = _NUM_RE.sub("<N>", msg)
    return msg.strip()


def parse_perf_opcode_xmls(
    common_xml: str,
    target_xml: Optional[str] = None,
) -> dict[str, dict[str, Any]]:
    """Parse Qualcomm Perf HAL resource config XMLs into '<major>,<minor>' -> node metadata."""
    mapping: dict[str, dict[str, Any]] = {}

    def _consume(xml_text: str, source_name: str) -> None:
        if not xml_text or not xml_text.strip():
            return
        cur_major: Optional[int] = None
        try:
            root = ET.fromstring(xml_text)
            elements = root.iter()
            for elem in elements:
                tag = elem.tag.split("}")[-1]
                if tag == "Major":
                    raw_val = elem.attrib.get("OpcodeValue", "")
                    try:
                        cur_major = int(raw_val, 0)
                    except ValueError:
                        cur_major = None
                elif tag == "Minor" and cur_major is not None:
                    raw_minor = elem.attrib.get("OpcodeValue", "")
                    try:
                        minor_int = int(raw_minor, 0)
                    except ValueError:
                        continue
                    key = f"{cur_major},{minor_int}"
                    mapping[key] = {
                        "major": cur_major,
                        "minor": minor_int,
                        "node": elem.attrib.get("Node", ""),
                        "supported": elem.attrib.get("Supported", "yes").lower(),
                        "source": source_name,
                    }
            return
        except ET.ParseError:
            pass

        # Fallback sequential tag regex parser if vendor XML has quirks.
        tag_re = re.compile(r"<(Major|Minor)\b([^>]*)/?>")
        attr_re = re.compile(r'(\w+)\s*=\s*"([^"]*)"')
        for m in tag_re.finditer(xml_text):
            kind = m.group(1)
            attrs = dict(attr_re.findall(m.group(2)))
            if kind == "Major":
                try:
                    cur_major = int(attrs.get("OpcodeValue", ""), 0)
                except ValueError:
                    cur_major = None
            elif kind == "Minor" and cur_major is not None:
                try:
                    minor_int = int(attrs.get("OpcodeValue", ""), 0)
                except ValueError:
                    continue
                key = f"{cur_major},{minor_int}"
                mapping[key] = {
                    "major": cur_major,
                    "minor": minor_int,
                    "node": attrs.get("Node", ""),
                    "supported": attrs.get("Supported", "yes").lower(),
                    "source": source_name,
                }

    _consume(common_xml, "commonresourceconfigs.xml")
    if target_xml:
        _consume(target_xml, "targetresourceconfigs.xml")
    return mapping


def parse_logcat_monotonic(lines: list[str]) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    for raw in lines:
        m = LOGCAT_MONOTONIC_RE.match(raw)
        if not m:
            continue
        entries.append(
            {
                "mono_s": float(m.group(1)),
                "pid": int(m.group(2)),
                "tid": int(m.group(3)),
                "level": m.group(4),
                "tag": m.group(5).strip(),
                "message": m.group(6).rstrip(),
            }
        )
    return entries


def detect_periodic_logcat_spinners(
    lines: list[str],
    min_count: int = 3,
    max_cv: float = 0.25,
) -> dict[str, Any]:
    """Identify noisy log tags and low-variance periodic spinners from monotonic logcat lines."""
    entries = parse_logcat_monotonic(lines)
    if not entries:
        return {
            "total_lines": 0,
            "duration_s": 0.0,
            "top_spammers": [],
            "periodic_spinners": [],
        }

    t_min = min(e["mono_s"] for e in entries)
    t_max = max(e["mono_s"] for e in entries)
    duration_s = max(t_max - t_min, 0.001)

    grouped: dict[tuple[int, str, str], list[float]] = defaultdict(list)
    by_tag: dict[tuple[int, str], int] = defaultdict(int)
    samples: dict[tuple[int, str, str], str] = {}

    for e in entries:
        tmpl = normalize_log_message(e["message"])
        key = (e["pid"], e["tag"], tmpl)
        grouped[key].append(e["mono_s"])
        by_tag[(e["pid"], e["tag"])] += 1
        samples.setdefault(key, e["message"][:160])

    periodic_spinners: list[dict[str, Any]] = []
    for (pid, tag, tmpl), ts_list in grouped.items():
        if len(ts_list) < min_count:
            continue
        ts_sorted = sorted(ts_list)
        dts = [b - a for a, b in zip(ts_sorted[:-1], ts_sorted[1:]) if b > a]
        if len(dts) < min_count - 1:
            continue
        mean_dt = sum(dts) / len(dts)
        if not (0.2 <= mean_dt <= 30.0):
            continue
        var = sum((dt - mean_dt) ** 2 for dt in dts) / len(dts)
        std_dt = math.sqrt(var)
        cv = std_dt / mean_dt if mean_dt > 0 else 999.0
        if cv <= max_cv:
            periodic_spinners.append(
                {
                    "pid": pid,
                    "tag": tag,
                    "count": len(ts_list),
                    "period_mean_s": round(mean_dt, 3),
                    "period_std_s": round(std_dt, 3),
                    "cv": round(cv, 3),
                    "template": tmpl,
                    "sample": samples[(pid, tag, tmpl)],
                }
            )

    periodic_spinners.sort(key=lambda x: (-x["count"], x["cv"]))

    top_spammers = [
        {
            "pid": pid,
            "tag": tag,
            "count": count,
            "lines_per_min": round(count / duration_s * 60.0, 1),
        }
        for (pid, tag), count in sorted(by_tag.items(), key=lambda kv: -kv[1])[:20]
    ]

    return {
        "total_lines": len(entries),
        "duration_s": round(duration_s, 3),
        "top_spammers": top_spammers,
        "periodic_spinners": periodic_spinners,
    }


def compute_thread_deltas(
    snap0: dict[str, dict[str, Any]],
    snap1: dict[str, dict[str, Any]],
    elapsed_s: float,
) -> list[dict[str, Any]]:
    """Compute per-thread CPU runtime, scheduler delay, and context-switch deltas."""
    elapsed = max(elapsed_s, 0.001)
    rows: list[dict[str, Any]] = []
    for tid_key, t1 in snap1.items():
        t0 = snap0.get(tid_key)
        if not t0:
            continue
        d_run_ns = t1["run_ns"] - t0["run_ns"]
        d_delay_ns = t1["delay_ns"] - t0["delay_ns"]
        d_slices = t1["slices"] - t0["slices"]
        if d_run_ns < 0 or d_slices < 0:
            continue
        if d_run_ns == 0 and d_slices == 0:
            continue
        cpu_ms = d_run_ns / 1e6
        delay_ms = d_delay_ns / 1e6
        rows.append(
            {
                "pid": t1["pid"],
                "tid": t1["tid"],
                "proc_name": t1.get("proc_name", ""),
                "comm": t1.get("comm", ""),
                "cpu_ms": round(cpu_ms, 2),
                "cpu_pct": round((cpu_ms / (elapsed * 1000.0)) * 100.0, 2),
                "run_delay_ms": round(delay_ms, 2),
                "sched_slices": d_slices,
                "slices_per_s": round(d_slices / elapsed, 2),
            }
        )
    rows.sort(key=lambda r: (-r["cpu_ms"], -r["slices_per_s"]))
    return rows


class EnvAuditor:
    """Layer 0 auditor checking cpufreq caps, sysfs locks, ZUXOS policies, thermal, and app config."""

    def __init__(self, adb: AdbClient) -> None:
        self.adb = adb

    def run(self, package: str, out_dir: Optional[Path] = None) -> dict[str, Any]:
        script = f"""
        PKG={shlex.quote(package)}
        echo "=== META ==="
        echo "model=$(getprop ro.product.model)"
        echo "device=$(getprop ro.product.device)"
        echo "soc=$(getprop ro.soc.model)"
        echo "android=$(getprop ro.build.version.release)"
        echo "sdk=$(getprop ro.build.version.sdk)"
        echo "display_id=$(getprop ro.build.display.id)"
        echo "kernel=$(uname -r)"
        echo "id=$(id)"

        echo "=== CPU_TOPOLOGY ==="
        for p in /sys/devices/system/cpu/cpufreq/policy*; do
          [ -d "$p" ] || continue
          pn=$(basename "$p")
          echo "POLICY:$pn|cpus=$(cat $p/related_cpus 2>/dev/null)|gov=$(cat $p/scaling_governor 2>/dev/null)|cur=$(cat $p/scaling_cur_freq 2>/dev/null)|min=$(cat $p/scaling_min_freq 2>/dev/null)|max=$(cat $p/scaling_max_freq 2>/dev/null)|hwmax=$(cat $p/cpuinfo_max_freq 2>/dev/null)|max_mode=$(stat -c '%a' $p/scaling_max_freq 2>/dev/null)|min_mode=$(stat -c '%a' $p/scaling_min_freq 2>/dev/null)"
          for wn in adaptive_low_freq adaptive_high_freq boost hispeed_freq; do
            wf="$p/walt/$wn"
            if [ -e "$wf" ]; then
              echo "WALT:$pn/$wn|mode=$(stat -c '%a' $wf 2>/dev/null)|val=$(cat $wf 2>/dev/null)"
            fi
          done
        done
        for c in /sys/devices/system/cpu/cpu[0-9]*; do
          [ -f "$c/cpu_capacity" ] && echo "CAP:$(basename $c)=$(cat $c/cpu_capacity 2>/dev/null)"
        done

        echo "=== GPU ==="
        echo "gpuclk=$(cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null)"
        echo "max_gpuclk=$(cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null)"
        echo "gpu_busy=$(cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null)"

        echo "=== THERMAL ==="
        for cd in /sys/class/thermal/cooling_device*; do
          [ -d "$cd" ] || continue
          cur=$(cat $cd/cur_state 2>/dev/null)
          mx=$(cat $cd/max_state 2>/dev/null)
          tp=$(cat $cd/type 2>/dev/null)
          if [ -n "$cur" ] && [ "$cur" != "0" ]; then
            echo "COOLING:$tp|$cur|$mx"
          fi
        done
        dumpsys thermalservice 2>/dev/null | grep -E "ThermalStatus|Temperature\\{{" | head -n 25

        echo "=== CONFOUNDERS ==="
        echo "power_saving=$(settings get system artificial_switch_of_power_saving 2>/dev/null)"
        echo "low_power=$(settings get global low_power 2>/dev/null)"
        ps -Ao pid,user,pcpu,time,args 2>/dev/null | grep -iE "scene-daemon|omarea\\.vtools|kernellog|cnss_diag|oh_my_keymint|soterta\\.sh|logcat.*newlog" | grep -v grep

        echo "=== TARGET_PACKAGE ==="
        dumpsys package "$PKG" 2>/dev/null | grep -E "versionCode=|versionName=|flags=\\[|Dexopt state:|primary-abi|\\[status=" | head -n 15
        ZPID=$(pidof "$PKG" 2>/dev/null | awk '{{print $1}}')
        echo "ZPID=$ZPID"
        if [ -n "$ZPID" ] && [ -d "/proc/$ZPID/task" ]; then
          echo "CGROUP=$(cat /proc/$ZPID/cgroup 2>/dev/null | tr '\n' ';')"
          for t in /proc/$ZPID/task/*; do
            tid=$(basename "$t")
            comm=$(cat "$t/comm" 2>/dev/null)
            case "$tid:$comm" in
              "$ZPID:"*|*:RenderThread|*:hwuiTask*|*:HeapAnalyzer|*:LeakCanary*)
                cpus=$(grep '^Cpus_allowed_list:' "$t/status" 2>/dev/null | awk '{{print $2}}')
                prio=$(awk '/^prio / {{print $3}}' "$t/sched" 2>/dev/null)
                umin=$(awk '/^effective uclamp.min / {{print $4}}' "$t/sched" 2>/dev/null)
                umax=$(awk '/^effective uclamp.max / {{print $4}}' "$t/sched" 2>/dev/null)
                exec_ms=$(awk '/^se.sum_exec_runtime / {{print $3}}' "$t/sched" 2>/dev/null)
                echo "TTHREAD:$tid|$comm|$cpus|$prio|$umin|$umax|$exec_ms"
                ;;
            esac
          done
        fi

        echo "=== ZUXOS_SCHED ==="
        echo "hyper_enable=$(cat /proc/lenovo_hyperschedule/enable 2>/dev/null)"
        echo "in_hyper_xml=$(grep -c "$PKG" /system/etc/hyper_scheduler_config.xml 2>/dev/null || echo 0)"
        echo "in_zuiboost_xml=$(grep -c "$PKG" /system/etc/zuiboostperformance.xml 2>/dev/null || echo 0)"
        echo "in_perfboost_xml=$(grep -c "$PKG" /vendor/etc/perf/perfboostsconfig.xml 2>/dev/null || echo 0)"
        echo "--- HYPER_UX_STAT ---"
        cat /proc/lenovo_hyperschedule/ux_stat 2>/dev/null | head -n 40
        echo "--- ZUIBOOST ---"
        dumpsys zuiboostperformanceservice 2>/dev/null
        echo "--- ZUIDYNAMIC ---"
        dumpsys zuidynamicperformanceservice 2>/dev/null
        echo "--- ADPF_HINT ---"
        dumpsys performance_hint 2>/dev/null
        echo "--- SURFACEFLINGER ---"
        dumpsys SurfaceFlinger 2>/dev/null | grep -E "activeMode=|mWorkDuration|mReadyDuration|FrameRateOverrides|enableFrameRateOverride" | head -n 15
        echo "--- ZUIBOOST_TID_CHECK ---"
        tids_line=$(dumpsys zuiboostperformanceservice 2>/dev/null | grep "Active TIDs:")
        for tid in $(echo "$tids_line" | tr -cd '0-9 '); do
          if [ -d "/proc/$tid" ] || ls -d /proc/*/task/$tid >/dev/null 2>&1; then
            echo "ZTID:$tid=alive"
          else
            echo "ZTID:$tid=dead"
          fi
        done
        """
        res = self.adb.run_shell(script, timeout=25.0, check=True)
        report = self._parse_env_output(res.stdout, package)

        opcode_res = self.adb.run_shell(
            """
            echo "===COMMON_XML==="
            cat /vendor/etc/perf/commonresourceconfigs.xml 2>/dev/null
            echo "===TARGET_XML==="
            cat /vendor/etc/perf/targetresourceconfigs.xml 2>/dev/null
            """,
            timeout=15.0,
        )
        common_xml, target_xml = "", ""
        if "===TARGET_XML===" in opcode_res.stdout:
            part_c, target_xml = opcode_res.stdout.split("===TARGET_XML===", 1)
            common_xml = part_c.replace("===COMMON_XML===", "")
        opcode_map = parse_perf_opcode_xmls(common_xml, target_xml)
        report["perf_opcode_count"] = len(opcode_map)

        if out_dir is not None:
            out_dir.mkdir(parents=True, exist_ok=True)
            (out_dir / "env_audit.json").write_text(
                json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8"
            )
            (out_dir / "perf_opcode_map.json").write_text(
                json.dumps(opcode_map, indent=2, ensure_ascii=False), encoding="utf-8"
            )
        return report

    @staticmethod
    def _parse_env_output(stdout: str, package: str) -> dict[str, Any]:
        sections: dict[str, list[str]] = defaultdict(list)
        cur = "ROOT"
        for line in stdout.splitlines():
            s = line.strip()
            if s.startswith("=== ") and s.endswith(" ==="):
                cur = s[4:-4].strip()
                continue
            if s:
                sections[cur].append(s)

        meta: dict[str, str] = {}
        for line in sections["META"]:
            if "=" in line:
                k, v = line.split("=", 1)
                meta[k] = v.strip()

        policies: dict[str, dict[str, Any]] = {}
        walt_nodes: list[dict[str, str]] = []
        cpu_caps: dict[str, int] = {}
        for line in sections["CPU_TOPOLOGY"]:
            if line.startswith("POLICY:"):
                kv = dict(p.split("=", 1) for p in line[7:].split("|") if "=" in p)
                pname = line[7:].split("|", 1)[0]
                policies[pname] = {
                    "cpus": kv.get("cpus", ""),
                    "gov": kv.get("gov", ""),
                    "cur_khz": int(kv["cur"]) if kv.get("cur", "").isdigit() else 0,
                    "min_khz": int(kv["min"]) if kv.get("min", "").isdigit() else 0,
                    "max_khz": int(kv["max"]) if kv.get("max", "").isdigit() else 0,
                    "hwmax_khz": int(kv["hwmax"]) if kv.get("hwmax", "").isdigit() else 0,
                    "max_mode": kv.get("max_mode", ""),
                    "min_mode": kv.get("min_mode", ""),
                }
            elif line.startswith("WALT:"):
                name_part, rest = line[5:].split("|", 1)
                kv = dict(p.split("=", 1) for p in rest.split("|") if "=" in p)
                walt_nodes.append({"node": name_part, **kv})
            elif line.startswith("CAP:"):
                k, v = line[4:].split("=", 1)
                if v.isdigit():
                    cpu_caps[k] = int(v)

        thermal_active_cooling: list[str] = []
        thermal_lines: list[str] = []
        for line in sections["THERMAL"]:
            if line.startswith("COOLING:"):
                thermal_active_cooling.append(line[8:])
            else:
                thermal_lines.append(line)

        confounders_procs: list[str] = []
        power_saving = None
        low_power = None
        for line in sections["CONFOUNDERS"]:
            if line.startswith("power_saving="):
                power_saving = line.split("=", 1)[1].strip()
            elif line.startswith("low_power="):
                low_power = line.split("=", 1)[1].strip()
            else:
                confounders_procs.append(line)

        pkg_lines: list[str] = []
        target_threads: list[dict[str, Any]] = []
        zpid = None
        cgroup_info = ""
        for line in sections["TARGET_PACKAGE"]:
            if line.startswith("ZPID="):
                val = line.split("=", 1)[1].strip()
                zpid = int(val) if val.isdigit() else None
            elif line.startswith("CGROUP="):
                cgroup_info = line.split("=", 1)[1].strip()
            elif line.startswith("TTHREAD:"):
                parts = line[8:].split("|")
                if len(parts) >= 7:
                    target_threads.append(
                        {
                            "tid": int(parts[0]) if parts[0].isdigit() else parts[0],
                            "comm": parts[1],
                            "cpus_allowed": parts[2],
                            "prio": parts[3],
                            "uclamp_min": parts[4],
                            "uclamp_max": parts[5],
                            "exec_ms": float(parts[6]) if parts[6] else 0.0,
                        }
                    )
            else:
                pkg_lines.append(line)

        zux: dict[str, Any] = {
            "hyper_enable": "",
            "in_hyper_xml": False,
            "in_zuiboost_xml": False,
            "in_perfboost_xml": False,
            "ux_stat_lines": [],
            "zuiboost_dump": [],
            "zuidynamic_dump": [],
            "adpf_dump": [],
            "surfaceflinger": [],
            "zuiboost_dead_tids": [],
            "zuiboost_alive_tids": [],
        }
        sub = ""
        for line in sections["ZUXOS_SCHED"]:
            if line.startswith("--- ") and line.endswith(" ---"):
                sub = line[4:-4]
                continue
            if not sub:
                if line.startswith("hyper_enable="):
                    zux["hyper_enable"] = line.split("=", 1)[1]
                elif line.startswith("in_hyper_xml="):
                    zux["in_hyper_xml"] = line.split("=", 1)[1].strip() not in ("", "0")
                elif line.startswith("in_zuiboost_xml="):
                    zux["in_zuiboost_xml"] = line.split("=", 1)[1].strip() not in ("", "0")
                elif line.startswith("in_perfboost_xml="):
                    zux["in_perfboost_xml"] = line.split("=", 1)[1].strip() not in ("", "0")
            elif sub == "HYPER_UX_STAT":
                zux["ux_stat_lines"].append(line)
            elif sub == "ZUIBOOST":
                zux["zuiboost_dump"].append(line)
            elif sub == "ZUIDYNAMIC":
                zux["zuidynamic_dump"].append(line)
            elif sub == "ADPF_HINT":
                zux["adpf_dump"].append(line)
            elif sub == "SURFACEFLINGER":
                zux["surfaceflinger"].append(line)
            elif sub == "ZUIBOOST_TID_CHECK" and line.startswith("ZTID:"):
                tid_s, st = line[5:].split("=", 1)
                if st == "dead":
                    zux["zuiboost_dead_tids"].append(int(tid_s))
                else:
                    zux["zuiboost_alive_tids"].append(int(tid_s))

        # Generate rule-based Layer 0 findings.
        findings: list[dict[str, str]] = []
        capped_policies = [
            f"{pn}: {p['max_khz']}/{p['hwmax_khz']} kHz"
            for pn, p in policies.items()
            if p["hwmax_khz"] > 0 and p["max_khz"] < p["hwmax_khz"]
        ]
        if capped_policies and not thermal_active_cooling:
            findings.append(
                {
                    "severity": "CRITICAL",
                    "category": "cpu_freq_cap",
                    "title": "Silent CPU frequency cap without active thermal throttling",
                    "detail": "; ".join(capped_policies),
                }
            )

        ro_nodes = [
            f"{pn}/scaling_max_freq(0{p['max_mode']})"
            for pn, p in policies.items()
            if p["max_mode"] in ("444", "0444", "440", "400")
        ] + [
            f"{w['node']}(0{w.get('mode')})"
            for w in walt_nodes
            if w.get("mode") in ("444", "0444", "440", "400")
        ]
        if ro_nodes:
            findings.append(
                {
                    "severity": "CRITICAL",
                    "category": "sysfs_permission_lock",
                    "title": "Read-only cpufreq/walt sysfs nodes detected (blocks perf2-hal updates)",
                    "detail": ", ".join(ro_nodes),
                }
            )

        if power_saving == "1" or low_power == "1":
            findings.append(
                {
                    "severity": "WARN",
                    "category": "power_saving",
                    "title": "Power-saving setting enabled",
                    "detail": f"artificial_switch_of_power_saving={power_saving}, low_power={low_power}",
                }
            )

        if any("scene-daemon" in p or "vtools" in p for p in confounders_procs):
            findings.append(
                {
                    "severity": "WARN",
                    "category": "third_party_tuner",
                    "title": "Third-party performance daemon/app running",
                    "detail": "Active Scene/vtools daemon may override cpufreq/sysfs permissions.",
                }
            )

        pkg_joined = "\n".join(pkg_lines)
        if "DEBUGGABLE" in pkg_joined:
            findings.append(
                {
                    "severity": "WARN",
                    "category": "app_build_debuggable",
                    "title": f"{package} is DEBUGGABLE",
                    "detail": "Debuggable ART flags and instrumentation can add UI latency.",
                }
            )
        if "status=run-from-apk" in pkg_joined or "status=verify" in pkg_joined:
            findings.append(
                {
                    "severity": "WARN",
                    "category": "dexopt_uncompiled",
                    "title": "App is not AOT-compiled (run-from-apk / verify)",
                    "detail": "JIT compilation & class verification will cause navigation/scroll jank spikes.",
                }
            )
        leak_threads = [t for t in target_threads if "HeapAnalyzer" in t["comm"] or "LeakCanary" in t["comm"]]
        if leak_threads:
            total_leak_ms = sum(t["exec_ms"] for t in leak_threads)
            findings.append(
                {
                    "severity": "WARN",
                    "category": "leakcanary_overhead",
                    "title": "LeakCanary threads active inside target process",
                    "detail": f"Cumulative CPU={total_leak_ms:.1f}ms across {[t['comm'] for t in leak_threads]}",
                }
            )

        if not zux["in_hyper_xml"] and not zux["in_zuiboost_xml"]:
            findings.append(
                {
                    "severity": "INFO",
                    "category": "zuxos_whitelist",
                    "title": f"{package} is outside ZUXOS HyperSchedule & ZuiBoost whitelists",
                    "detail": "No static_ux or automatic big-core affinity boost is granted by OEM configs.",
                }
            )
        if zux["zuiboost_dead_tids"]:
            findings.append(
                {
                    "severity": "WARN",
                    "category": "zuiboost_tid_leak",
                    "title": "ZuiBoostPerformanceService holds dead TIDs",
                    "detail": f"Dead TIDs still listed: {zux['zuiboost_dead_tids']}",
                }
            )

        adpf_joined = "\n".join(zux["adpf_dump"])
        if zpid is not None and str(zpid) not in adpf_joined:
            findings.append(
                {
                    "severity": "INFO",
                    "category": "adpf_missing",
                    "title": f"No active ADPF HintSession for {package} (PID {zpid})",
                    "detail": "Target process is not using PerformanceHintManager/HWUI ADPF on this session.",
                }
            )

        return {
            "package": package,
            "meta": meta,
            "policies": policies,
            "walt_nodes": walt_nodes,
            "cpu_capacities": cpu_caps,
            "thermal_active_cooling": thermal_active_cooling,
            "thermal_lines": thermal_lines,
            "confounders": {
                "power_saving": power_saving,
                "low_power": low_power,
                "processes": confounders_procs,
            },
            "target_package": {
                "pid": zpid,
                "cgroup": cgroup_info,
                "package_lines": pkg_lines,
                "threads": target_threads,
            },
            "zuxos_scheduler": zux,
            "findings": findings,
        }


class IdleSpinAuditor:
    """Layer 4 auditor detecting continuously spinning threads and periodic log spammers."""

    def __init__(self, adb: AdbClient) -> None:
        self.adb = adb

    def _snapshot_threads(self) -> tuple[float, dict[str, dict[str, Any]]]:
        script = """
        echo "UPTIME=$(cut -d' ' -f1 /proc/uptime)"
        echo "===PROCS==="
        ps -Ao pid,args 2>/dev/null
        echo "===COMM==="
        grep -H "" /proc/[0-9]*/task/*/comm 2>/dev/null || true
        echo "===SCHEDSTAT==="
        grep -H "" /proc/[0-9]*/task/*/schedstat 2>/dev/null || true
        """
        res = self.adb.run_shell(script, timeout=20.0, check=True)
        uptime_s = 0.0
        proc_names: dict[int, str] = {}
        comms: dict[str, str] = {}
        threads: dict[str, dict[str, Any]] = {}
        section = ""
        for raw in res.stdout.splitlines():
            line = raw.strip()
            if not line:
                continue
            if line.startswith("UPTIME="):
                uptime_s = float(line.split("=", 1)[1])
                continue
            if line in ("===PROCS===", "===COMM===", "===SCHEDSTAT==="):
                section = line
                continue
            if section == "===PROCS===":
                parts = line.split(None, 1)
                if len(parts) == 2 and parts[0].isdigit():
                    proc_names[int(parts[0])] = parts[1][:120]
            elif section == "===COMM===":
                # /proc/3086/task/3086/comm:system_server
                if ":" not in line:
                    continue
                path_part, val = line.split(":", 1)
                segs = path_part.split("/")
                if len(segs) >= 5 and segs[2].isdigit() and segs[4].isdigit():
                    comms[f"{segs[2]}:{segs[4]}"] = val.strip()
            elif section == "===SCHEDSTAT===":
                if ":" not in line:
                    continue
                path_part, val = line.split(":", 1)
                segs = path_part.split("/")
                if len(segs) >= 5 and segs[2].isdigit() and segs[4].isdigit():
                    nums = val.split()
                    if len(nums) >= 3:
                        pid = int(segs[2])
                        tid = int(segs[4])
                        key = f"{pid}:{tid}"
                        threads[key] = {
                            "pid": pid,
                            "tid": tid,
                            "proc_name": proc_names.get(pid, ""),
                            "comm": comms.get(key, ""),
                            "run_ns": int(nums[0]),
                            "delay_ns": int(nums[1]),
                            "slices": int(nums[2]),
                        }
        return uptime_s, threads

    def run(
        self,
        idle_seconds: float = 15.0,
        out_dir: Optional[Path] = None,
    ) -> dict[str, Any]:
        work_dir = out_dir if out_dir is not None else Path.cwd()
        work_dir.mkdir(parents=True, exist_ok=True)
        log_path = work_dir / "idle_logcat.txt"
        pid_file = "/data/local/tmp/ztool_idle_logcat.pid"

        log_stream = self.adb.start_stream(
            f"echo $$ > {shlex.quote(pid_file)}; exec logcat -v monotonic -b main -b system -b crash -T 1",
            local_stdout_path=log_path,
            remote_pid_file=pid_file,
        )
        try:
            u0, snap0 = self._snapshot_threads()
            time.sleep(max(1.0, idle_seconds))
            u1, snap1 = self._snapshot_threads()
        finally:
            self.adb.stop_stream(log_stream)

        elapsed = max(u1 - u0, 0.5)
        deltas = compute_thread_deltas(snap0, snap1, elapsed)
        top_by_switches = sorted(deltas, key=lambda r: (-r["slices_per_s"], -r["cpu_ms"]))[:20]

        log_lines = log_path.read_text(encoding="utf-8", errors="replace").splitlines()
        log_analysis = detect_periodic_logcat_spinners(log_lines)

        result = {
            "elapsed_s": round(elapsed, 3),
            "total_threads_sampled": len(snap1),
            "top_cpu_threads": deltas[:25],
            "top_wakeup_threads": top_by_switches,
            "logcat_analysis": log_analysis,
        }
        if out_dir is not None:
            (out_dir / "idle_audit.json").write_text(
                json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8"
            )
        return result
