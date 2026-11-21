"""CLI entrypoint for ZTool + ZUXOS full-stack UI performance audit."""

from __future__ import annotations

import argparse
import json
import signal
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

from .adb_utils import AdbClient, AdbError
from .analyzer import PerfAuditAnalyzer
from .auditors import EnvAuditor, IdleSpinAuditor
from .collectors import CaptureSession
from .state_guard import DeviceStateGuard

DEFAULT_PACKAGE = "com.qimian233.ztool"
DEFAULT_RUNS_ROOT = Path(__file__).resolve().parent / "runs"


def _default_run_dir(prefix: str) -> Path:
    stamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    return DEFAULT_RUNS_ROOT / f"{stamp}_{prefix}"


def _cmd_env_check(args: argparse.Namespace) -> int:
    adb = AdbClient(args.serial)
    is_root, id_str = adb.check_root()
    if not is_root:
        raise AdbError(f"Root shell required for full audit, got: {id_str}")
    out_dir = Path(args.out_dir) if args.out_dir else _default_run_dir("env")
    report = EnvAuditor(adb).run(package=args.package, out_dir=out_dir)

    print(f"[env-check] Output: {out_dir / 'env_audit.json'}")
    print(
        f"[env-check] Device: {report['meta'].get('model')} ({report['meta'].get('soc')}), "
        f"Android {report['meta'].get('android')} SDK {report['meta'].get('sdk')}, "
        f"ZUXOS {report['meta'].get('display_id')}"
    )
    print(f"[env-check] Perf HAL opcode map entries: {report.get('perf_opcode_count', 0)}")
    for f in report.get("findings", []):
        print(f"  [{f['severity']}] {f['title']}: {f['detail']}")
    return 0


def _cmd_idle_audit(args: argparse.Namespace) -> int:
    adb = AdbClient(args.serial)
    is_root, id_str = adb.check_root()
    if not is_root:
        raise AdbError(f"Root shell required, got: {id_str}")
    out_dir = Path(args.out_dir) if args.out_dir else _default_run_dir("idle")
    res = IdleSpinAuditor(adb).run(idle_seconds=args.idle_seconds, out_dir=out_dir)
    print(f"[idle-audit] Output: {out_dir / 'idle_audit.json'} (elapsed={res['elapsed_s']}s)")
    print("[idle-audit] Top CPU threads:")
    for t in res["top_cpu_threads"][:10]:
        print(f"  PID {t['pid']} TID {t['tid']} [{t['comm']}] ({t['proc_name']}): {t['cpu_ms']}ms ({t['cpu_pct']}%), {t['slices_per_s']} slices/s")
    spinners = res["logcat_analysis"].get("periodic_spinners", [])
    if spinners:
        print("[idle-audit] Periodic log spinners:")
        for sp in spinners[:10]:
            print(f"  PID {sp['pid']} {sp['tag']} x{sp['count']} every {sp['period_mean_s']}s (cv={sp['cv']}): {sp['template']}")
    return 0


def _cmd_capture(args: argparse.Namespace) -> int:
    adb = AdbClient(args.serial)
    is_root, id_str = adb.check_root()
    if not is_root:
        raise AdbError(f"Root shell required, got: {id_str}")

    out_dir = Path(args.out_dir) if args.out_dir else _default_run_dir(args.mode)
    out_dir.mkdir(parents=True, exist_ok=True)
    backup_path = out_dir / "state_backup.json"

    print(f"[capture] Device={adb.serial}, package={args.package}, mode={args.mode}, workload={args.workload}")
    print(f"[capture] Output dir: {out_dir}")

    print("[capture] Step 1/5: Environment hygiene check...")
    env_report = EnvAuditor(adb).run(package=args.package, out_dir=out_dir)
    for f in env_report.get("findings", []):
        print(f"  [{f['severity']}] {f['title']}: {f['detail']}")

    if not args.skip_idle:
        print(f"[capture] Step 2/5: Idle-spin baseline audit ({args.idle_seconds}s)...")
        IdleSpinAuditor(adb).run(idle_seconds=args.idle_seconds, out_dir=out_dir)
    else:
        print("[capture] Step 2/5: Skipped idle-spin baseline (--skip-idle).")

    guard = DeviceStateGuard(adb)
    print(f"[capture] Step 3/5: Snapshotting device state -> {backup_path.name} (mode={args.mode})...")
    guard.apply_mode(
        mode=args.mode,
        backup_path=backup_path,
        package=args.package,
        pause_scene=args.pause_scene,
    )

    interrupted = False

    def _handle_signal(signum: int, _frame: Any) -> None:
        nonlocal interrupted
        interrupted = True
        raise KeyboardInterrupt(f"Signal {signum}")

    prev_int = signal.signal(signal.SIGINT, _handle_signal)
    prev_term = signal.signal(signal.SIGTERM, _handle_signal)

    restore_res = None
    capture_ok = False
    try:
        print(f"[capture] Step 4/5: Running synchronized multi-source capture ({args.duration}s)...")
        post_hook = (
            (lambda: guard.boost_package_threads(backup_path, args.package))
            if args.mode == "boost-ab"
            else None
        )
        CaptureSession(adb).run(
            package=args.package,
            run_dir=out_dir,
            workload=args.workload,
            duration_s=args.duration,
            post_launch_hook=post_hook,
        )
        capture_ok = True
    finally:
        signal.signal(signal.SIGINT, prev_int)
        signal.signal(signal.SIGTERM, prev_term)
        print("[capture] Restoring device performance state and verifying...")
        try:
            restore_res = guard.restore(backup_path)
            (out_dir / "restore_result.json").write_text(
                json.dumps(restore_res.to_dict(), indent=2, ensure_ascii=False),
                encoding="utf-8",
            )
            status_word = "PASS" if restore_res.ok else "FAIL"
            print(
                f"[capture] Restore verification: {status_word} "
                f"(checked={restore_res.checked_count}, mismatches={len(restore_res.mismatches)})"
            )
            for mm in restore_res.mismatches:
                print(
                    f"  MISMATCH {mm.target}.{mm.field}: expected={mm.expected!r}, actual={mm.actual!r}"
                )
        except Exception as exc:  # noqa: BLE001
            print(f"[capture] ERROR during restore: {exc}", file=sys.stderr)
            print(f"[capture] Manual recovery command: python -m tools.perf_audit restore --state {backup_path}", file=sys.stderr)

    if interrupted or not capture_ok:
        return 1

    print("[capture] Step 5/5: Correlating Perfetto + root ftrace + sidecar + logcat + gfxinfo...")
    summary = PerfAuditAnalyzer(out_dir, package=args.package).analyze()
    print("\n=== EXECUTIVE SUMMARY ===")
    for bullet in summary.get("verdict", []):
        print(f"  * {bullet}")
    print(f"\nReport written to: {out_dir / 'report.md'}")
    if restore_res is not None and not restore_res.ok:
        return 2
    return 0


def _cmd_analyze(args: argparse.Namespace) -> int:
    run_dir = Path(args.run_dir)
    if not run_dir.is_dir():
        raise FileNotFoundError(f"Run directory not found: {run_dir}")
    summary = PerfAuditAnalyzer(run_dir, package=args.package).analyze()
    print("=== EXECUTIVE SUMMARY ===")
    for bullet in summary.get("verdict", []):
        print(f"  * {bullet}")
    print(f"\nReport written to: {run_dir / 'report.md'}")
    return 0


def _cmd_restore(args: argparse.Namespace) -> int:
    backup_path = Path(args.state)
    if not backup_path.is_file():
        raise FileNotFoundError(f"Backup state JSON not found: {backup_path}")
    adb = AdbClient(args.serial)
    is_root, id_str = adb.check_root()
    if not is_root:
        raise AdbError(f"Root shell required, got: {id_str}")
    res = DeviceStateGuard(adb).restore(backup_path)
    print(
        f"[restore] {'PASS' if res.ok else 'FAIL'} "
        f"(checked={res.checked_count}, mismatches={len(res.mismatches)})"
    )
    for mm in res.mismatches:
        print(f"  MISMATCH {mm.target}.{mm.field}: expected={mm.expected!r}, actual={mm.actual!r}")
    return 0 if res.ok else 2


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="python -m tools.perf_audit",
        description="Full-stack UI & ZUXOS scheduler performance diagnostic CLI for ZTool.",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_env = sub.add_parser("env-check", help="Run Layer 0 device/ZUXOS hygiene check.")
    p_env.add_argument("-s", "--serial", default=None, help="ADB device serial.")
    p_env.add_argument("-p", "--package", default=DEFAULT_PACKAGE, help="Target Android package.")
    p_env.add_argument("-o", "--out-dir", default=None, help="Output directory.")
    p_env.set_defaults(func=_cmd_env_check)

    p_idle = sub.add_parser("idle-audit", help="Run Layer 4 idle-spin & logcat spinner audit.")
    p_idle.add_argument("-s", "--serial", default=None, help="ADB device serial.")
    p_idle.add_argument("--idle-seconds", type=float, default=15.0, help="Idle observation duration in seconds.")
    p_idle.add_argument("-o", "--out-dir", default=None, help="Output directory.")
    p_idle.set_defaults(func=_cmd_idle_audit)

    p_cap = sub.add_parser("capture", help="Run synchronized multi-source capture & analysis.")
    p_cap.add_argument("-s", "--serial", default=None, help="ADB device serial.")
    p_cap.add_argument("-p", "--package", default=DEFAULT_PACKAGE, help="Target Android package.")
    p_cap.add_argument(
        "--mode",
        choices=("baseline", "clean-env", "boost-ab"),
        default="baseline",
        help="Execution mode (clean-env and boost-ab always snapshot and restore state).",
    )
    p_cap.add_argument(
        "--pause-scene",
        action="store_true",
        help="Pause scene-daemon with SIGSTOP during clean-env/boost-ab and resume on restore.",
    )
    p_cap.add_argument(
        "--workload",
        choices=("manual", "auto-scroll"),
        default="auto-scroll",
        help="Workload driver during capture.",
    )
    p_cap.add_argument("--duration", type=float, default=15.0, help="Capture workload duration (seconds).")
    p_cap.add_argument("--skip-idle", action="store_true", help="Skip pre-capture idle-spin audit.")
    p_cap.add_argument("--idle-seconds", type=float, default=15.0, help="Idle audit duration if enabled.")
    p_cap.add_argument("-o", "--out-dir", default=None, help="Output directory.")
    p_cap.set_defaults(func=_cmd_capture)

    p_ana = sub.add_parser("analyze", help="Re-run offline correlation & Markdown report on an existing run directory.")
    p_ana.add_argument("run_dir", help="Path to existing run directory.")
    p_ana.add_argument("-p", "--package", default=None, help="Override target package name.")
    p_ana.set_defaults(func=_cmd_analyze)

    p_res = sub.add_parser("restore", help="Manually restore device state from a state_backup.json snapshot.")
    p_res.add_argument("--state", required=True, help="Path to state_backup.json.")
    p_res.add_argument("-s", "--serial", default=None, help="ADB device serial.")
    p_res.set_defaults(func=_cmd_restore)

    return parser


def main(argv: Optional[list[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
