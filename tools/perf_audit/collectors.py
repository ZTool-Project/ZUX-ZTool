"""Synchronized multi-source collectors and reproducible Navigation/Scroll workload."""

from __future__ import annotations

import json
import re
import shlex
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any, Callable, Optional

from .adb_utils import AdbClient

DESIRED_ATRACE_CATEGORIES = (
    "gfx",
    "view",
    "input",
    "am",
    "wm",
    "res",
    "dalvik",
    "binder_driver",
    "sched",
    "freq",
    "idle",
    "hal",
    "aidl",
    "power",
    "ss",
)

ROOT_FTRACE_EVENTS = (
    "schedwalt/waltgov_next_freq",
    "schedwalt/sched_set_boost",
    "schedwalt/sched_set_preferred_cluster",
    "schedwalt/sched_pipeline_tasks",
    "schedwalt/core_ctl_set_boost",
    "schedux/dynamic_ux_set",
    "schedux/dynamic_ux_unset",
    "power/cpu_frequency_limits",
    "dcvsh/dcvsh_throttle",
    "dcvsh/dcvsh_freq",
    "thermal/cdev_update",
    "thermal/thermal_power_cpu_limit",
    "kgsl/kgsl_gpu_frequency",
    "kgsl/kgsl_clock_throttling",
    "sde/sde_encoder_underrun",
    "avc/selinux_audited",
)

FTRACE_INSTANCE_NAME = "ztool_sched"


def build_perfetto_config(
    package: str,
    atrace_categories: list[str],
    duration_ms: int,
) -> str:
    """Build Perfetto .pbtxt trace config tailored for UI Navigation & scheduling correlation."""
    cats_block = "\n".join(
        f'      atrace_categories: "{cat}"' for cat in atrace_categories
    )
    return f"""buffers {{
  size_kb: 98304
  fill_policy: RING_BUFFER
}}
buffers {{
  size_kb: 16384
  fill_policy: RING_BUFFER
}}

data_sources {{
  config {{
    name: "linux.ftrace"
    target_buffer: 0
    ftrace_config {{
{cats_block}
      atrace_apps: "{package}"
      buffer_size_kb: 32768
      drain_period_ms: 250
    }}
  }}
}}

data_sources {{
  config {{
    name: "linux.process_stats"
    target_buffer: 1
    process_stats_config {{
      scan_all_processes_on_start: true
    }}
  }}
}}

data_sources {{
  config {{
    name: "android.surfaceflinger.frametimeline"
    target_buffer: 1
  }}
}}

data_sources {{
  config {{
    name: "android.gpu.memory"
    target_buffer: 1
  }}
}}

data_sources {{
  config {{
    name: "android.packages_list"
    target_buffer: 1
  }}
}}

data_sources {{
  config {{
    name: "android.log"
    target_buffer: 1
    android_log_config {{
      log_ids: LID_DEFAULT
      log_ids: LID_SYSTEM
      log_ids: LID_CRASH
    }}
  }}
}}

duration_ms: {int(duration_ms)}
flush_period_ms: 1000
"""


def parse_sidecar_text(raw_text: str) -> list[dict[str, Any]]:
    """Parse raw sched_sidecar.txt ticks into structured JSON records."""
    ticks: list[dict[str, Any]] = []
    cur: Optional[dict[str, Any]] = None
    in_ux = False
    for raw_line in raw_text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("TICK_START "):
            in_ux = False
            try:
                uptime_s = float(line.split(None, 1)[1])
            except (IndexError, ValueError):
                uptime_s = 0.0
            cur = {
                "uptime_s": uptime_s,
                "policies": {},
                "gpu": {},
                "threads": [],
                "ux_stat": [],
            }
        elif line == "TICK_END" and cur is not None:
            ticks.append(cur)
            cur = None
            in_ux = False
        elif cur is None:
            continue
        elif line == "UX_START":
            in_ux = True
        elif line == "UX_END":
            in_ux = False
        elif in_ux:
            cur["ux_stat"].append(line)
        elif line.startswith("PF:"):
            # PF:policy0|min=364800|max=2265600|cur=1344000
            parts = line[3:].split("|")
            pname = parts[0]
            pdict: dict[str, int] = {}
            for kv in parts[1:]:
                if "=" in kv:
                    k, v = kv.split("=", 1)
                    if v.strip().isdigit():
                        pdict[k] = int(v.strip())
            cur["policies"][pname] = pdict
        elif line.startswith("GPU:"):
            for kv in line[4:].split("|"):
                if "=" in kv:
                    k, v = kv.split("=", 1)
                    cur["gpu"][k] = v.strip()
        elif line.startswith("TH:"):
            # TH:<tid>|<comm>|<cpus_allowed>|<prio>|<umin>|<umax>|<cpuset>
            parts = line[3:].split("|")
            if len(parts) >= 7:
                cur["threads"].append(
                    {
                        "tid": int(parts[0]) if parts[0].isdigit() else parts[0],
                        "comm": parts[1],
                        "cpus_allowed": parts[2],
                        "prio": parts[3],
                        "uclamp_min": parts[4],
                        "uclamp_max": parts[5],
                        "cpuset": parts[6],
                    }
                )
    return ticks


def parse_time_in_state_raw(raw_text: str) -> dict[str, dict[str, Any]]:
    """Parse /proc/<pid>/task/<tid>/time_in_state dump per thread."""
    threads: dict[str, dict[str, Any]] = {}
    cur_tid: Optional[str] = None
    cur_comm = ""
    cur_cpu = ""
    for raw_line in raw_text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith("TIS_THREAD:"):
            parts = line[11:].split("|", 1)
            cur_tid = parts[0]
            cur_comm = parts[1] if len(parts) > 1 else ""
            cur_cpu = ""
            threads[cur_tid] = {"tid": int(cur_tid) if cur_tid.isdigit() else cur_tid, "comm": cur_comm, "clusters": {}}
        elif cur_tid is not None:
            if line.startswith("cpu"):
                cur_cpu = line
                threads[cur_tid]["clusters"].setdefault(cur_cpu, {})
            elif cur_cpu:
                parts = line.split()
                if len(parts) == 2 and parts[0].isdigit() and parts[1].isdigit():
                    threads[cur_tid]["clusters"][cur_cpu][int(parts[0])] = int(parts[1])
    return threads


def _discover_nav_targets(adb: AdbClient, package: str) -> list[tuple[int, int, str]]:
    """Discover clickable bottom-bar / navigation-rail coordinates before starting trace."""
    res = adb.run_shell(
        """
        rm -f /data/local/tmp/ztool_uidump.xml
        uiautomator dump /data/local/tmp/ztool_uidump.xml >/dev/null 2>&1
        cat /data/local/tmp/ztool_uidump.xml 2>/dev/null
        rm -f /data/local/tmp/ztool_uidump.xml
        """,
        timeout=12.0,
    )
    xml_start = res.stdout.find("<?xml")
    if xml_start < 0:
        return []
    xml_text = res.stdout[xml_start:]
    bounds_re = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")
    targets: list[tuple[int, int, str]] = []
    seen_labels: set[str] = set()
    preferred_labels = {
        "首页", "功能", "设置", "日志", "Home", "Features", "Settings", "Logs",
    }
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError:
        return []

    w, h = adb.get_screen_size()
    for node in root.iter("node"):
        if node.attrib.get("package") not in ("", package):
            continue
        label = (node.attrib.get("text") or node.attrib.get("content-desc") or "").strip()
        if not label:
            continue
        m = bounds_re.search(node.attrib.get("bounds", ""))
        if not m:
            continue
        x1, y1, x2, y2 = map(int, m.groups())
        cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
        if label in preferred_labels and label not in seen_labels:
            seen_labels.add(label)
            targets.append((cx, cy, f"tab:{label}"))
        elif (
            node.attrib.get("clickable") == "true"
            and (cy > int(h * 0.80) or cx < int(w * 0.14))
            and label not in seen_labels
            and len(targets) < 5
        ):
            seen_labels.add(label)
            targets.append((cx, cy, f"nav:{label}"))
    return targets[:4]


class CaptureSession:
    """Orchestrates synchronized Perfetto, root ftrace, sidecar, logcat, and workload capture."""

    def __init__(self, adb: AdbClient) -> None:
        self.adb = adb

    def _get_supported_atrace_categories(self) -> list[str]:
        res = self.adb.run_shell("atrace --list_categories", timeout=10.0, check=True)
        supported = set()
        for line in res.stdout.splitlines():
            token = line.strip().split(" - ", 1)[0].strip()
            if token:
                supported.add(token)
        return [c for c in DESIRED_ATRACE_CATEGORIES if c in supported]

    def _setup_root_ftrace_instance(self) -> list[str]:
        inst_dir = f"/sys/kernel/tracing/instances/{FTRACE_INSTANCE_NAME}"
        events_str = " ".join(ROOT_FTRACE_EVENTS)
        script = f"""
        INST={shlex.quote(inst_dir)}
        if [ -d "$INST" ]; then
          echo 0 > "$INST/tracing_on" 2>/dev/null || true
          echo "" > "$INST/set_event" 2>/dev/null || true
          rmdir "$INST" 2>/dev/null || true
        fi
        mkdir -p "$INST"
        echo 0 > "$INST/tracing_on"
        echo boot > "$INST/trace_clock" 2>/dev/null || true
        echo 16384 > "$INST/buffer_size_kb" 2>/dev/null || true
        echo "" > "$INST/trace" 2>/dev/null || true
        for ev in {events_str}; do
          ep="$INST/events/$ev/enable"
          if [ -e "$ep" ]; then
            echo 1 > "$ep" 2>/dev/null && echo "ENABLED:$ev" || echo "FAILED:$ev"
          else
            echo "MISSING:$ev"
          fi
        done
        echo 1 > "$INST/tracing_on"
        """
        res = self.adb.run_shell(script, timeout=15.0, check=True)
        enabled = [
            line.split(":", 1)[1].strip()
            for line in res.stdout.splitlines()
            if line.startswith("ENABLED:")
        ]
        return enabled

    def _teardown_root_ftrace_instance(self) -> None:
        inst_dir = f"/sys/kernel/tracing/instances/{FTRACE_INSTANCE_NAME}"
        self.adb.run_shell(
            f"""
            INST={shlex.quote(inst_dir)}
            if [ -d "$INST" ]; then
              echo 0 > "$INST/tracing_on" 2>/dev/null || true
              echo "" > "$INST/set_event" 2>/dev/null || true
              rmdir "$INST" 2>/dev/null || true
            fi
            """,
            timeout=10.0,
        )

    def _snapshot_time_in_state(self, package: str) -> dict[str, dict[str, Any]]:
        res = self.adb.run_shell(
            f"""
            ZPID=$(pidof {shlex.quote(package)} 2>/dev/null | awk '{{print $1}}')
            if [ -n "$ZPID" ] && [ -d "/proc/$ZPID/task" ]; then
              for t in /proc/$ZPID/task/*; do
                tid=$(basename "$t")
                comm=$(cat "$t/comm" 2>/dev/null)
                case "$tid:$comm" in
                  "$ZPID:"*|*:RenderThread|*:hwuiTask*)
                    echo "TIS_THREAD:$tid|$comm"
                    cat "$t/time_in_state" 2>/dev/null
                    ;;
                esac
              done
            fi
            """,
            timeout=10.0,
        )
        return parse_time_in_state_raw(res.stdout)

    def _snapshot_scheduler_dumpsys(self, package: str) -> dict[str, Any]:
        res = self.adb.run_shell(
            f"""
            echo "===ZUIBOOST==="
            dumpsys zuiboostperformanceservice 2>/dev/null
            echo "===ADPF==="
            dumpsys performance_hint 2>/dev/null
            echo "===PKG_PID==="
            pidof {shlex.quote(package)} 2>/dev/null
            """,
            timeout=10.0,
        )
        return {"raw": res.stdout}

    def _run_auto_scroll_workload(
        self,
        package: str,
        duration_s: float,
        nav_targets: list[tuple[int, int, str]],
    ) -> list[dict[str, Any]]:
        w, h = self.adb.get_screen_size()
        cx = int(w * 0.52)
        y_low = int(h * 0.74)
        y_high = int(h * 0.26)
        events: list[dict[str, Any]] = []
        deadline = time.monotonic() + duration_s
        step = 0
        tab_idx = 0

        while time.monotonic() < deadline:
            u_res = self.adb.run_shell("cut -d' ' -f1 /proc/uptime", timeout=4.0)
            uptime_s = float(u_res.stdout.strip()) if u_res.stdout.strip() else 0.0

            if step % 4 == 1 and nav_targets:
                tx, ty, label = nav_targets[tab_idx % len(nav_targets)]
                tab_idx += 1
                self.adb.run_shell(f"input tap {tx} {ty}", timeout=5.0)
                events.append({"uptime_s": uptime_s, "action": "tap", "target": label, "x": tx, "y": ty})
                time.sleep(0.55)
            elif step % 2 == 0:
                self.adb.run_shell(f"input swipe {cx} {y_low} {cx} {y_high} 180", timeout=6.0)
                events.append({"uptime_s": uptime_s, "action": "fling_down"})
                time.sleep(0.65)
            else:
                self.adb.run_shell(f"input swipe {cx} {y_high} {cx} {y_low} 180", timeout=6.0)
                events.append({"uptime_s": uptime_s, "action": "fling_up"})
                time.sleep(0.65)
            step += 1
        return events

    def run(
        self,
        package: str,
        run_dir: Path,
        workload: str = "auto-scroll",
        duration_s: float = 15.0,
        post_launch_hook: Optional[Callable[[], None]] = None,
    ) -> dict[str, Any]:
        run_dir.mkdir(parents=True, exist_ok=True)
        remote_cfg = "/data/local/tmp/ztool_audit_trace.cfg"
        remote_trace = "/data/misc/perfetto-traces/ztool_audit.perfetto-trace"
        remote_perf_pid = "/data/local/tmp/ztool_audit_perfetto.pid"
        remote_ftrace_pid = "/data/local/tmp/ztool_audit_ftrace.pid"
        remote_logcat_pid = "/data/local/tmp/ztool_audit_logcat.pid"
        remote_sidecar_stop = "/data/local/tmp/ztool_audit_sidecar.stop"

        # Bring target app up before reset/capture so gfxinfo & Navigation targets are valid.
        launch_info = self.adb.launch_package(package)
        time.sleep(1.5)
        if post_launch_hook is not None:
            post_launch_hook()

        nav_targets: list[tuple[int, int, str]] = []
        if workload == "auto-scroll":
            nav_targets = _discover_nav_targets(self.adb, package)

        # Reset gfxinfo & capture T0 baselines.
        self.adb.run_shell(f"dumpsys gfxinfo {shlex.quote(package)} reset >/dev/null 2>&1", timeout=10.0)
        tis_before = self._snapshot_time_in_state(package)
        sched_before = self._snapshot_scheduler_dumpsys(package)

        cats = self._get_supported_atrace_categories()
        # Give Perfetto a safety margin above workload duration; we interrupt it cleanly right after SYNC_END.
        pbtxt = build_perfetto_config(
            package=package,
            atrace_categories=cats,
            duration_ms=int((duration_s + 25.0) * 1000),
        )
        local_cfg = run_dir / "trace_config.pbtxt"
        local_cfg.write_text(pbtxt, encoding="utf-8", newline="\n")
        self.adb.push(local_cfg, remote_cfg)
        self.adb.rm_remote(remote_trace, remote_sidecar_stop, remote_perf_pid, remote_ftrace_pid, remote_logcat_pid)

        enabled_ftrace_events = self._setup_root_ftrace_instance()

        perfetto_log_path = run_dir / "perfetto_stdout.log"
        ftrace_path = run_dir / "root_ftrace.txt"
        sidecar_raw_path = run_dir / "sched_sidecar.txt"
        logcat_path = run_dir / "logcat_monotonic.txt"

        perf_stream = None
        ftrace_stream = None
        sidecar_stream = None
        logcat_stream = None
        anchors: dict[str, Any] = {}
        workload_events: list[dict[str, Any]] = []

        try:
            perf_stream = self.adb.start_stream(
                f"echo $$ > {shlex.quote(remote_perf_pid)}; exec perfetto -c {shlex.quote(remote_cfg)} --txt -o {shlex.quote(remote_trace)}",
                local_stdout_path=perfetto_log_path,
                remote_pid_file=remote_perf_pid,
            )
            ftrace_stream = self.adb.start_stream(
                f"echo $$ > {shlex.quote(remote_ftrace_pid)}; exec cat /sys/kernel/tracing/instances/{FTRACE_INSTANCE_NAME}/trace_pipe",
                local_stdout_path=ftrace_path,
                remote_pid_file=remote_ftrace_pid,
            )
            logcat_stream = self.adb.start_stream(
                f"echo $$ > {shlex.quote(remote_logcat_pid)}; exec logcat -v monotonic -b main -b system -b crash -T 1",
                local_stdout_path=logcat_path,
                remote_pid_file=remote_logcat_pid,
            )

            sidecar_script = f"""
            PKG={shlex.quote(package)}
            STOP={shlex.quote(remote_sidecar_stop)}
            ZPID=$(pidof "$PKG" 2>/dev/null | awk '{{print $1}}')
            while [ ! -f "$STOP" ]; do
              read u _ < /proc/uptime
              echo "TICK_START $u"
              for p in /sys/devices/system/cpu/cpufreq/policy*; do
                [ -d "$p" ] || continue
                pn=${{p##*/}}
                read pmin < "$p/scaling_min_freq" 2>/dev/null || pmin=""
                read pmax < "$p/scaling_max_freq" 2>/dev/null || pmax=""
                read pcur < "$p/scaling_cur_freq" 2>/dev/null || pcur=""
                echo "PF:$pn|min=$pmin|max=$pmax|cur=$pcur"
              done
              read gclk < /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null || gclk=""
              read gbusy < /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null || gbusy=""
              echo "GPU:clk=$gclk|busy=$gbusy"
              if [ -z "$ZPID" ] || [ ! -d "/proc/$ZPID/task" ]; then
                ZPID=$(pidof "$PKG" 2>/dev/null | awk '{{print $1}}')
              fi
              if [ -n "$ZPID" ] && [ -d "/proc/$ZPID/task" ]; then
                for t in /proc/$ZPID/task/*; do
                  tid=${{t##*/}}
                  read comm < "$t/comm" 2>/dev/null || continue
                  case "$tid:$comm" in
                    "$ZPID:"*|*:RenderThread|*:hwuiTask*)
                      cpus=$(grep '^Cpus_allowed_list:' "$t/status" 2>/dev/null | awk '{{print $2}}')
                      prio=$(awk '/^prio / {{print $3}}' "$t/sched" 2>/dev/null)
                      umin=$(awk '/^effective uclamp.min / {{print $4}}' "$t/sched" 2>/dev/null)
                      umax=$(awk '/^effective uclamp.max / {{print $4}}' "$t/sched" 2>/dev/null)
                      cset=$(grep ':cpuset:' "$t/cgroup" 2>/dev/null | cut -d: -f3)
                      echo "TH:$tid|$comm|$cpus|$prio|$umin|$umax|$cset"
                      ;;
                  esac
                done
              fi
              if [ -r /proc/lenovo_hyperschedule/ux_stat ]; then
                echo "UX_START"
                head -n 25 /proc/lenovo_hyperschedule/ux_stat 2>/dev/null
                echo "UX_END"
              fi
              echo "TICK_END"
              sleep 0.15
            done
            """
            sidecar_stream = self.adb.start_stream(
                sidecar_script,
                local_stdout_path=sidecar_raw_path,
                remote_stop_file=remote_sidecar_stop,
            )

            # Give perfetto / ftrace / logcat producers 0.8s to initialize before SYNC_START.
            time.sleep(0.8)
            anchors["sync_start"] = self.adb.emit_sync_anchor(
                "ZTOOL_PERF_SYNC_START", ftrace_instance=FTRACE_INSTANCE_NAME
            )

            if workload == "manual":
                print(
                    f"\n>>> [MANUAL WORKLOAD] Interact with '{package}' (Navigation + Scrolling) "
                    f"for {duration_s:.0f}s..."
                )
                time.sleep(duration_s)
            elif workload == "auto-scroll":
                workload_events = self._run_auto_scroll_workload(
                    package=package,
                    duration_s=duration_s,
                    nav_targets=nav_targets,
                )
            else:
                raise ValueError(f"Unsupported workload: {workload}")

            anchors["sync_end"] = self.adb.emit_sync_anchor(
                "ZTOOL_PERF_SYNC_END", ftrace_instance=FTRACE_INSTANCE_NAME
            )
            time.sleep(0.3)
        finally:
            if sidecar_stream is not None:
                self.adb.stop_stream(sidecar_stream, timeout=4.0)
            if logcat_stream is not None:
                self.adb.stop_stream(logcat_stream, timeout=4.0)
            if ftrace_stream is not None:
                self.adb.stop_stream(ftrace_stream, timeout=4.0)
            self._teardown_root_ftrace_instance()
            if perf_stream is not None:
                # Signal Perfetto with SIGINT first so it flushes buffers before exit.
                self.adb.run_shell(
                    f"""
                    if [ -f {shlex.quote(remote_perf_pid)} ]; then
                      p=$(cat {shlex.quote(remote_perf_pid)} 2>/dev/null)
                      [ -n "$p" ] && kill -INT "$p" 2>/dev/null || true
                    fi
                    """,
                    timeout=5.0,
                )
                self.adb.stop_stream(perf_stream, timeout=12.0)

        # Post-workload dumpsys & time_in_state snapshots.
        gfx_res = self.adb.run_shell(
            f"dumpsys gfxinfo {shlex.quote(package)} framestats", timeout=15.0
        )
        (run_dir / "gfxinfo_framestats.txt").write_text(
            gfx_res.stdout, encoding="utf-8", newline="\n"
        )
        tis_after = self._snapshot_time_in_state(package)
        sched_after = self._snapshot_scheduler_dumpsys(package)

        # Pull perfetto trace & clean up remote artifacts.
        local_trace = run_dir / "trace.perfetto-trace"
        self.adb.pull(remote_trace, local_trace, timeout=120.0)
        self.adb.rm_remote(remote_cfg, remote_trace)

        # Convert sidecar raw text to JSONL.
        sidecar_ticks = parse_sidecar_text(
            sidecar_raw_path.read_text(encoding="utf-8", errors="replace")
        )
        (run_dir / "sched_sidecar.jsonl").write_text(
            "\n".join(json.dumps(t, ensure_ascii=False) for t in sidecar_ticks)
            + ("\n" if sidecar_ticks else ""),
            encoding="utf-8",
            newline="\n",
        )

        metadata = {
            "package": package,
            "workload": workload,
            "duration_s": duration_s,
            "launch_info": launch_info,
            "nav_targets": [{"x": x, "y": y, "label": lbl} for x, y, lbl in nav_targets],
            "atrace_categories": cats,
            "enabled_ftrace_events": enabled_ftrace_events,
            "anchors": anchors,
            "workload_events": workload_events,
            "sidecar_tick_count": len(sidecar_ticks),
            "time_in_state_before": tis_before,
            "time_in_state_after": tis_after,
            "scheduler_dumpsys_before": sched_before,
            "scheduler_dumpsys_after": sched_after,
        }
        (run_dir / "capture_meta.json").write_text(
            json.dumps(metadata, indent=2, ensure_ascii=False),
            encoding="utf-8",
            newline="\n",
        )
        return metadata
