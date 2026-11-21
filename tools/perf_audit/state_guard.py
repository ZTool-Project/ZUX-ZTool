"""Transactional device performance-state snapshot, mutation, and verified restore."""

from __future__ import annotations

import json
import os
import shlex
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Optional

from .adb_utils import AdbClient

TRACKED_WALT_NODES = (
    "adaptive_low_freq",
    "adaptive_high_freq",
    "boost",
    "hispeed_freq",
    "hispeed_load",
    "up_rate_limit_us",
    "down_rate_limit_us",
)

TRACKED_CPUCTL_GROUPS = ("top-app", "foreground")
TRACKED_CPUCTL_FILES = (
    "cpu.uclamp.min",
    "cpu.uclamp.max",
    "cpu.uclamp.latency_sensitive",
)

TRACKED_SETTINGS = (
    ("system", "artificial_switch_of_power_saving"),
    ("global", "low_power"),
)


@dataclass
class RestoreMismatch:
    target: str
    field: str
    expected: str
    actual: str


@dataclass
class RestoreVerificationResult:
    ok: bool
    mode: str
    checked_count: int
    mismatches: list[RestoreMismatch]
    notes: list[str]

    def to_dict(self) -> dict[str, Any]:
        return {
            "ok": self.ok,
            "mode": self.mode,
            "checked_count": self.checked_count,
            "mismatches": [asdict(m) for m in self.mismatches],
            "notes": list(self.notes),
        }


def _norm_mode(raw: str) -> str:
    raw = raw.strip()
    if not raw:
        return ""
    try:
        return f"{int(raw, 8):04o}"
    except ValueError:
        return raw


def _norm_hex_mask(raw: str) -> str:
    cleaned = raw.strip().replace(",", "").lower().lstrip("0")
    return cleaned or "0"


def _norm_uclamp(raw: str) -> str:
    raw = raw.strip()
    if raw == "max":
        return "max"
    try:
        return f"{float(raw):.2f}"
    except ValueError:
        return raw


def _atomic_write_json(path: Path, data: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, indent=2, ensure_ascii=False)
        fh.flush()
        os.fsync(fh.fileno())
    os.replace(tmp, path)


def _pick_freq_at_ratio(avail: list[int], max_freq: int, ratio: float) -> int:
    if not avail:
        return int(max_freq * ratio)
    target = max_freq * ratio
    for freq in sorted(avail):
        if freq >= target:
            return freq
    return sorted(avail)[-1]


def build_restore_script(snapshot: dict[str, Any]) -> str:
    """Build deterministic POSIX sh commands to revert every mutation captured in snapshot."""
    lines: list[str] = ["set +e"]
    applied = snapshot.get("applied_mutations", {})

    # 1. Restore thread CPU affinities for surviving TIDs.
    for tid_str, info in snapshot.get("thread_affinities", {}).items():
        orig_mask = _norm_hex_mask(str(info.get("cpus_allowed_hex", "")))
        if not orig_mask:
            continue
        tid_q = shlex.quote(str(tid_str))
        lines.append(
            f'if [ -d "/proc/{tid_str}" ] || ls -d /proc/*/task/{tid_str} >/dev/null 2>&1; then '
            f'taskset -p {shlex.quote(orig_mask)} {tid_q} >/dev/null 2>&1 || true; fi'
        )

    # 2. Restore /proc/lenovo_hyperschedule/static_ux entries that we modified.
    for tid_str, prev_val in applied.get("static_ux_tids", {}).items():
        lines.append(
            'if [ -w /proc/lenovo_hyperschedule/static_ux ]; then '
            f'printf "p %s %s\\n" {shlex.quote(str(tid_str))} {shlex.quote(str(prev_val))} '
            '> /proc/lenovo_hyperschedule/static_ux 2>/dev/null || true; fi'
        )

    # 3. Restore cpuctl uclamp nodes.
    for path, meta in snapshot.get("cpuctl", {}).items():
        val = str(meta.get("value", ""))
        mode = _norm_mode(str(meta.get("mode", "")))
        p_q = shlex.quote(path)
        if val != "":
            lines.append(
                f'if [ -e {p_q} ]; then chmod 0644 {p_q} 2>/dev/null || true; '
                f'printf "%s" {shlex.quote(val)} > {p_q} 2>/dev/null || true; fi'
            )
        if mode:
            lines.append(f'if [ -e {p_q} ]; then chmod {mode} {p_q} 2>/dev/null || true; fi')

    # 4. Restore cpufreq policies in strict kernel-safe order:
    #    ensure writable -> lower scaling_min_freq to cpuinfo_min_freq ->
    #    restore scaling_max_freq -> restore scaling_min_freq ->
    #    restore walt/* values -> finally restore exact octal permissions.
    for policy_name, pdata in sorted(snapshot.get("policies", {}).items()):
        nodes = pdata.get("nodes", {})
        min_meta = nodes.get("scaling_min_freq")
        max_meta = nodes.get("scaling_max_freq")
        cpuinfo_min = str(pdata.get("cpuinfo_min_freq", "")).strip()

        if min_meta and max_meta:
            min_p = shlex.quote(min_meta["path"])
            max_p = shlex.quote(max_meta["path"])
            orig_min = str(min_meta["value"]).strip()
            orig_max = str(max_meta["value"]).strip()
            lines.append(f"chmod 0644 {min_p} {max_p} 2>/dev/null || true")
            if cpuinfo_min:
                lines.append(f'printf "%s" {shlex.quote(cpuinfo_min)} > {min_p} 2>/dev/null || true')
            if orig_max:
                lines.append(f'printf "%s" {shlex.quote(orig_max)} > {max_p} 2>/dev/null || true')
            if orig_min:
                lines.append(f'printf "%s" {shlex.quote(orig_min)} > {min_p} 2>/dev/null || true')

        for rel_name, meta in nodes.items():
            if rel_name in ("scaling_min_freq", "scaling_max_freq"):
                continue
            node_p = shlex.quote(meta["path"])
            orig_val = str(meta.get("value", "")).strip()
            if rel_name in applied.get("touched_walt_nodes", []) and orig_val != "":
                lines.append(f"chmod 0644 {node_p} 2>/dev/null || true")
                lines.append(f'printf "%s" {shlex.quote(orig_val)} > {node_p} 2>/dev/null || true')

        # Restore original permissions last so 0444 locks do not block value restoration.
        for rel_name, meta in nodes.items():
            node_p = shlex.quote(meta["path"])
            orig_mode = _norm_mode(str(meta.get("mode", "")))
            if orig_mode:
                lines.append(f"if [ -e {node_p} ]; then chmod {orig_mode} {node_p} 2>/dev/null || true; fi")

    # 5. Restore msm_performance only if we touched it.
    if applied.get("touched_msm_perf"):
        for path, meta in snapshot.get("msm_performance", {}).items():
            p_q = shlex.quote(path)
            val = str(meta.get("value", "")).strip()
            mode = _norm_mode(str(meta.get("mode", "")))
            if val:
                lines.append(f"chmod 0644 {p_q} 2>/dev/null || true")
                lines.append(f'printf "%s" {shlex.quote(val)} > {p_q} 2>/dev/null || true')
            if mode:
                lines.append(f"chmod {mode} {p_q} 2>/dev/null || true")

    # 6. Restore Android settings.
    for key, orig_val in snapshot.get("settings", {}).items():
        ns, name = key.split(":", 1)
        if orig_val is None:
            lines.append(f"settings delete {shlex.quote(ns)} {shlex.quote(name)} >/dev/null 2>&1 || true")
        else:
            lines.append(
                f"settings put {shlex.quote(ns)} {shlex.quote(name)} {shlex.quote(str(orig_val))} >/dev/null 2>&1 || true"
            )

    # 7. Resume any paused background tuners (e.g. scene-daemon).
    for pid in applied.get("paused_pids", []):
        lines.append(f"kill -CONT {shlex.quote(str(pid))} 2>/dev/null || true")

    return "\n".join(lines) + "\n"


def verify_snapshot_diff(
    expected_snapshot: dict[str, Any],
    live_snapshot: dict[str, Any],
    mode_name: str,
) -> RestoreVerificationResult:
    """Compare live device state against pre-mutation snapshot."""
    mismatches: list[RestoreMismatch] = []
    notes: list[str] = []
    checked = 0

    applied = expected_snapshot.get("applied_mutations", {})
    touched_min_policies = set(applied.get("touched_min_freq_policies", []))
    legacy_touched_min = bool(applied.get("touched_min_freq", False))

    # Verify policy nodes (value + octal permissions).
    for pname, exp_p in expected_snapshot.get("policies", {}).items():
        live_p = live_snapshot.get("policies", {}).get(pname, {})
        exp_nodes = exp_p.get("nodes", {})
        live_nodes = live_p.get("nodes", {})
        for rel, exp_meta in exp_nodes.items():
            live_meta = live_nodes.get(rel, {})
            checked += 1
            exp_v = str(exp_meta.get("value", "")).strip()
            live_v = str(live_meta.get("value", "")).strip()
            if exp_v != live_v:
                is_touched_min = (pname in touched_min_policies) or (
                    legacy_touched_min and not touched_min_policies
                )
                if rel == "scaling_min_freq" and not is_touched_min:
                    notes.append(
                        f"Transient runtime drift on untouched {pname}/{rel}: {exp_v} -> {live_v}"
                    )
                else:
                    mismatches.append(
                        RestoreMismatch(f"{pname}/{rel}", "value", exp_v, live_v)
                    )
            exp_m = _norm_mode(str(exp_meta.get("mode", "")))
            live_m = _norm_mode(str(live_meta.get("mode", "")))
            if exp_m != live_m:
                mismatches.append(
                    RestoreMismatch(f"{pname}/{rel}", "mode", exp_m, live_m)
                )

    # Verify cpuctl.
    for path, exp_meta in expected_snapshot.get("cpuctl", {}).items():
        live_meta = live_snapshot.get("cpuctl", {}).get(path, {})
        checked += 1
        exp_v = _norm_uclamp(str(exp_meta.get("value", "")))
        live_v = _norm_uclamp(str(live_meta.get("value", "")))
        if exp_v != live_v:
            mismatches.append(RestoreMismatch(path, "value", exp_v, live_v))
        exp_m = _norm_mode(str(exp_meta.get("mode", "")))
        live_m = _norm_mode(str(live_meta.get("mode", "")))
        if exp_m != live_m:
            mismatches.append(RestoreMismatch(path, "mode", exp_m, live_m))

    # Verify settings.
    for key, exp_v in expected_snapshot.get("settings", {}).items():
        checked += 1
        live_v = live_snapshot.get("settings", {}).get(key)
        if exp_v != live_v:
            mismatches.append(
                RestoreMismatch(f"settings:{key}", "value", repr(exp_v), repr(live_v))
            )

    # Verify thread affinities for threads still alive in live snapshot.
    live_aff = live_snapshot.get("thread_affinities", {})
    for tid_str, exp_info in expected_snapshot.get("thread_affinities", {}).items():
        if tid_str not in live_aff:
            notes.append(f"TID {tid_str} ({exp_info.get('comm')}) exited before restore check")
            continue
        checked += 1
        exp_hex = _norm_hex_mask(str(exp_info.get("cpus_allowed_hex", "")))
        live_hex = _norm_hex_mask(str(live_aff[tid_str].get("cpus_allowed_hex", "")))
        if exp_hex != live_hex:
            mismatches.append(
                RestoreMismatch(
                    f"tid:{tid_str}({exp_info.get('comm')})",
                    "cpus_allowed",
                    exp_hex,
                    live_hex,
                )
            )

    return RestoreVerificationResult(
        ok=len(mismatches) == 0,
        mode=mode_name,
        checked_count=checked,
        mismatches=mismatches,
        notes=notes,
    )


class DeviceStateGuard:
    """Captures, mutates, restores, and verifies ZUXOS performance knobs transactionally."""

    def __init__(self, adb: AdbClient) -> None:
        self.adb = adb

    def read_live_state(self, package: str) -> dict[str, Any]:
        """Read current cpufreq, walt, msm_performance, cpuctl, settings, and target affinities."""
        walt_list = " ".join(TRACKED_WALT_NODES)
        cpuctl_groups = " ".join(TRACKED_CPUCTL_GROUPS)
        cpuctl_files = " ".join(TRACKED_CPUCTL_FILES)
        script = f"""
        PKG={shlex.quote(package)}
        for p in /sys/devices/system/cpu/cpufreq/policy*; do
          [ -d "$p" ] || continue
          pname=$(basename "$p")
          echo "POLICY_START:$pname"
          echo "related_cpus=$(cat $p/related_cpus 2>/dev/null)"
          echo "scaling_governor=$(cat $p/scaling_governor 2>/dev/null)"
          echo "cpuinfo_min_freq=$(cat $p/cpuinfo_min_freq 2>/dev/null)"
          echo "cpuinfo_max_freq=$(cat $p/cpuinfo_max_freq 2>/dev/null)"
          echo "avail_freqs=$(cat $p/scaling_available_frequencies 2>/dev/null)"
          for n in scaling_min_freq scaling_max_freq; do
            f="$p/$n"
            if [ -e "$f" ]; then
              m=$(stat -c '%a' "$f" 2>/dev/null)
              v=$(cat "$f" 2>/dev/null)
              echo "NODE:$n|$f|$m|$v"
            fi
          done
          for wn in {walt_list}; do
            f="$p/walt/$wn"
            if [ -e "$f" ]; then
              m=$(stat -c '%a' "$f" 2>/dev/null)
              v=$(cat "$f" 2>/dev/null)
              echo "NODE:walt/$wn|$f|$m|$v"
            fi
          done
          echo "POLICY_END:$pname"
        done

        for c in /sys/devices/system/cpu/cpu[0-9]*; do
          cn=$(basename "$c")
          if [ -f "$c/cpu_capacity" ]; then
            echo "CPUCAP:$cn=$(cat $c/cpu_capacity 2>/dev/null)"
          fi
        done

        for f in /sys/kernel/msm_performance/parameters/cpu_min_freq /sys/kernel/msm_performance/parameters/cpu_max_freq; do
          if [ -e "$f" ]; then
            m=$(stat -c '%a' "$f" 2>/dev/null)
            v=$(cat "$f" 2>/dev/null)
            echo "MSMPERF:$f|$m|$v"
          fi
        done

        for g in {cpuctl_groups}; do
          for cf in {cpuctl_files}; do
            f="/dev/cpuctl/$g/$cf"
            if [ -e "$f" ]; then
              m=$(stat -c '%a' "$f" 2>/dev/null)
              v=$(cat "$f" 2>/dev/null)
              echo "CPUCTL:$f|$m|$v"
            fi
          done
        done

        echo "SET:system:artificial_switch_of_power_saving=$(settings get system artificial_switch_of_power_saving 2>/dev/null)"
        echo "SET:global:low_power=$(settings get global low_power 2>/dev/null)"

        ZPID=$(pidof "$PKG" 2>/dev/null | awk '{{print $1}}')
        echo "ZPID=$ZPID"
        if [ -n "$ZPID" ] && [ -d "/proc/$ZPID/task" ]; then
          for t in /proc/$ZPID/task/*; do
            tid=$(basename "$t")
            comm=$(cat "$t/comm" 2>/dev/null)
            case "$tid:$comm" in
              "$ZPID:"*|*:RenderThread|*:hwuiTask*)
                hex=$(grep '^Cpus_allowed:' "$t/status" 2>/dev/null | awk '{{print $2}}')
                lst=$(grep '^Cpus_allowed_list:' "$t/status" 2>/dev/null | awk '{{print $2}}')
                echo "AFF:$tid|$comm|$hex|$lst"
                ;;
            esac
          done
        fi

        if [ -r /proc/lenovo_hyperschedule/static_ux ]; then
          while IFS= read -r line; do
            echo "STATIC_UX:$line"
          done < /proc/lenovo_hyperschedule/static_ux
        fi
        """
        res = self.adb.run_shell(script, timeout=15.0, check=True)
        return self._parse_live_state(res.stdout, package)

    @staticmethod
    def _parse_live_state(stdout: str, package: str) -> dict[str, Any]:
        state: dict[str, Any] = {
            "package": package,
            "policies": {},
            "cpu_capacities": {},
            "msm_performance": {},
            "cpuctl": {},
            "settings": {},
            "thread_affinities": {},
            "static_ux_raw": [],
            "applied_mutations": {
                "touched_walt_nodes": [],
                "touched_msm_perf": False,
                "paused_pids": [],
                "static_ux_tids": {},
            },
        }
        cur_policy: Optional[dict[str, Any]] = None
        for raw_line in stdout.splitlines():
            line = raw_line.strip()
            if not line:
                continue
            if line.startswith("POLICY_START:"):
                pname = line.split(":", 1)[1]
                cur_policy = {"nodes": {}}
                state["policies"][pname] = cur_policy
            elif line.startswith("POLICY_END:"):
                cur_policy = None
            elif cur_policy is not None and line.startswith("NODE:"):
                payload = line[5:]
                parts = payload.split("|", 3)
                if len(parts) == 4:
                    rel, path, mode, val = parts
                    cur_policy["nodes"][rel] = {
                        "path": path,
                        "mode": _norm_mode(mode),
                        "value": val.strip(),
                    }
            elif cur_policy is not None and "=" in line:
                k, v = line.split("=", 1)
                if k == "related_cpus":
                    cur_policy[k] = [int(x) for x in v.split() if x.isdigit()]
                elif k == "avail_freqs":
                    cur_policy["scaling_available_frequencies"] = [
                        int(x) for x in v.split() if x.isdigit()
                    ]
                elif k in ("cpuinfo_min_freq", "cpuinfo_max_freq"):
                    cur_policy[k] = int(v) if v.isdigit() else 0
                else:
                    cur_policy[k] = v.strip()
            elif line.startswith("CPUCAP:"):
                k, v = line[7:].split("=", 1)
                if v.strip().isdigit():
                    state["cpu_capacities"][k] = int(v.strip())
            elif line.startswith("MSMPERF:"):
                parts = line[8:].split("|", 2)
                if len(parts) == 3:
                    path, mode, val = parts
                    state["msm_performance"][path] = {
                        "mode": _norm_mode(mode),
                        "value": val.strip(),
                    }
            elif line.startswith("CPUCTL:"):
                parts = line[7:].split("|", 2)
                if len(parts) == 3:
                    path, mode, val = parts
                    state["cpuctl"][path] = {
                        "mode": _norm_mode(mode),
                        "value": val.strip(),
                    }
            elif line.startswith("SET:"):
                rest = line[4:]
                key, val = rest.split("=", 1)
                cleaned = val.strip()
                state["settings"][key] = None if cleaned in ("", "null") else cleaned
            elif line.startswith("AFF:"):
                parts = line[4:].split("|", 3)
                if len(parts) == 4:
                    tid, comm, hex_m, lst = parts
                    state["thread_affinities"][tid] = {
                        "comm": comm,
                        "cpus_allowed_hex": hex_m.strip(),
                        "cpus_allowed_list": lst.strip(),
                    }
            elif line.startswith("STATIC_UX:"):
                state["static_ux_raw"].append(line[10:].strip())
        return state

    def snapshot(self, backup_path: Path, package: str) -> dict[str, Any]:
        snap = self.read_live_state(package)
        _atomic_write_json(backup_path, snap)
        return snap

    def apply_mode(
        self,
        mode: str,
        backup_path: Path,
        package: str,
        pause_scene: bool = False,
    ) -> dict[str, Any]:
        """Snapshot original device state, then apply requested mode."""
        snap = self.snapshot(backup_path, package)
        snap["mode"] = mode
        if mode == "baseline":
            _atomic_write_json(backup_path, snap)
            return snap

        if mode not in ("clean-env", "boost-ab"):
            raise ValueError(f"Unsupported mode: {mode}")

        applied = snap["applied_mutations"]
        applied["touched_min_freq_policies"] = []
        cmds: list[str] = ["set +e"]

        # Optional: pause scene-daemon so it does not overwrite restored/cleaned sysfs values mid-run.
        if pause_scene:
            ps_res = self.adb.run_shell("pgrep -f scene-daemon 2>/dev/null || pidof scene-daemon 2>/dev/null", timeout=5.0)
            pids = [p.strip() for p in ps_res.stdout.split() if p.strip().isdigit()]
            if pids:
                applied["paused_pids"] = pids
                for pid in pids:
                    cmds.append(f"kill -STOP {shlex.quote(pid)} 2>/dev/null || true")

        # Clean-env mutations: uncap scaling_max_freq, unlock 0444 walt adaptive_* nodes,
        # and clear software power-saving flags.
        for pname, pdata in sorted(snap.get("policies", {}).items()):
            hw_max = int(pdata.get("cpuinfo_max_freq", 0))
            nodes = pdata.get("nodes", {})
            max_meta = nodes.get("scaling_max_freq")
            if max_meta and hw_max > 0:
                cur_max = int(max_meta["value"]) if str(max_meta["value"]).isdigit() else 0
                if cur_max != hw_max or max_meta.get("mode") != "0644":
                    max_p = shlex.quote(max_meta["path"])
                    cmds.append(f"chmod 0644 {max_p} 2>/dev/null || true")
                    cmds.append(f'printf "%s" {hw_max} > {max_p} 2>/dev/null || true')

            for walt_rel in ("walt/adaptive_low_freq", "walt/adaptive_high_freq"):
                wmeta = nodes.get(walt_rel)
                if wmeta and wmeta.get("mode") == "0444":
                    applied["touched_walt_nodes"].append(walt_rel)
                    cmds.append(f"chmod 0644 {shlex.quote(wmeta['path'])} 2>/dev/null || true")

        # Only touch msm_performance/cpu_max_freq if an active cap (< 2147483647) exists.
        msm_max_meta = snap.get("msm_performance", {}).get("/sys/kernel/msm_performance/parameters/cpu_max_freq")
        if msm_max_meta:
            parts = msm_max_meta.get("value", "").split()
            if any(":" in p and int(p.split(":")[1]) < 2147483647 for p in parts if p.split(":")[-1].isdigit()):
                applied["touched_msm_perf"] = True
                uncapped = " ".join(f"{p.split(':')[0]}:2147483647" for p in parts if ":" in p)
                if uncapped:
                    p_q = shlex.quote("/sys/kernel/msm_performance/parameters/cpu_max_freq")
                    cmds.append(f"chmod 0644 {p_q} 2>/dev/null || true")
                    cmds.append(f'printf "%s" {shlex.quote(uncapped)} > {p_q} 2>/dev/null || true')

        if snap.get("settings", {}).get("system:artificial_switch_of_power_saving") == "1":
            cmds.append("settings put system artificial_switch_of_power_saving 0 >/dev/null 2>&1 || true")
        if snap.get("settings", {}).get("global:low_power") == "1":
            cmds.append("settings put global low_power 0 >/dev/null 2>&1 || true")

        # Boost-AB extra mutations: uclamp floor, min_freq floor on non-little clusters.
        if mode == "boost-ab":
            top_uclamp_min = "/dev/cpuctl/top-app/cpu.uclamp.min"
            if top_uclamp_min in snap.get("cpuctl", {}):
                cmds.append(f'printf "40.00" > {shlex.quote(top_uclamp_min)} 2>/dev/null || true')
            top_lat = "/dev/cpuctl/top-app/cpu.uclamp.latency_sensitive"
            if top_lat in snap.get("cpuctl", {}):
                cmds.append(f'printf "1" > {shlex.quote(top_lat)} 2>/dev/null || true')

            caps = snap.get("cpu_capacities", {})
            min_cap = min(caps.values()) if caps else 0
            for pname, pdata in sorted(snap.get("policies", {}).items()):
                rel_cpus = pdata.get("related_cpus", [])
                p_caps = [caps.get(f"cpu{c}", 0) for c in rel_cpus]
                if p_caps and max(p_caps) > min_cap:
                    avail = pdata.get("scaling_available_frequencies", [])
                    hw_max = int(pdata.get("cpuinfo_max_freq", 0))
                    min_meta = pdata.get("nodes", {}).get("scaling_min_freq")
                    if min_meta and hw_max > 0:
                        target_min = _pick_freq_at_ratio(avail, hw_max, 0.60)
                        applied["touched_min_freq_policies"].append(pname)
                        min_p = shlex.quote(min_meta["path"])
                        cmds.append(f"chmod 0644 {min_p} 2>/dev/null || true")
                        cmds.append(f'printf "%s" {target_min} > {min_p} 2>/dev/null || true')

        _atomic_write_json(backup_path, snap)
        self.adb.run_shell("\n".join(cmds), timeout=15.0)
        if mode == "boost-ab":
            self.boost_package_threads(backup_path, package)
        return snap

    def boost_package_threads(self, backup_path: Path, package: str) -> None:
        """Pin target UI/Render/HWUI threads to non-little CPUs and tag HyperSchedule static_ux."""
        snap = json.loads(backup_path.read_text(encoding="utf-8"))
        caps = snap.get("cpu_capacities", {})
        if not caps:
            return
        min_cap = min(caps.values())
        big_cpus = [
            int(k.replace("cpu", ""))
            for k, cap in caps.items()
            if cap > min_cap and k.startswith("cpu")
        ]
        if not big_cpus:
            return
        mask = 0
        for cpu_id in big_cpus:
            mask |= 1 << cpu_id
        mask_hex = f"{mask:x}"

        live = self.read_live_state(package)
        existing_aff = snap.setdefault("thread_affinities", {})
        static_ux_tids = snap.setdefault("applied_mutations", {}).setdefault("static_ux_tids", {})

        # Parse current static_ux entries so we can restore non-zero values if any.
        cur_static: dict[str, str] = {}
        for line in live.get("static_ux_raw", []):
            # Example: "p pid=18354 static_hyper_ux=5"
            parts = line.split()
            pid_v = ux_v = None
            for part in parts:
                if part.startswith("pid="):
                    pid_v = part.split("=", 1)[1]
                elif part.startswith("static_hyper_ux="):
                    ux_v = part.split("=", 1)[1]
            if pid_v and ux_v:
                cur_static[pid_v] = ux_v

        cmds = ["set +e"]
        for tid_str, info in live.get("thread_affinities", {}).items():
            if tid_str not in existing_aff:
                existing_aff[tid_str] = info
            cmds.append(f"taskset -p {shlex.quote(mask_hex)} {shlex.quote(tid_str)} >/dev/null 2>&1 || true")
            comm = info.get("comm", "")
            if comm == "RenderThread" or tid_str == str(self.adb.get_pid(package) or ""):
                if tid_str not in static_ux_tids:
                    static_ux_tids[tid_str] = cur_static.get(tid_str, "0")
                cmds.append(
                    'if [ -w /proc/lenovo_hyperschedule/static_ux ]; then '
                    f'printf "p %s 5\\n" {shlex.quote(tid_str)} > /proc/lenovo_hyperschedule/static_ux 2>/dev/null || true; fi'
                )

        snap["applied_mutations"]["boost_mask_hex"] = mask_hex
        _atomic_write_json(backup_path, snap)
        self.adb.run_shell("\n".join(cmds), timeout=10.0)

    def restore(self, backup_path: Path) -> RestoreVerificationResult:
        """Restore device state from backup_path and verify every tracked node."""
        snap = json.loads(backup_path.read_text(encoding="utf-8"))
        mode = str(snap.get("mode", "baseline"))
        package = str(snap.get("package", "com.qimian233.ztool"))

        if mode == "baseline":
            return RestoreVerificationResult(
                ok=True,
                mode="baseline",
                checked_count=0,
                mismatches=[],
                notes=["Baseline mode is strictly read-only; no mutations applied."],
            )

        script = build_restore_script(snap)
        self.adb.run_shell(script, timeout=20.0)
        time.sleep(1.0)

        live = self.read_live_state(package)
        return verify_snapshot_diff(snap, live, mode_name=mode)
