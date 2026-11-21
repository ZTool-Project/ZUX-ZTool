"""ADB and root shell helpers with Windows-safe stdin script execution."""

from __future__ import annotations

import re
import shlex
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import IO, Optional


class AdbError(RuntimeError):
    """Raised when an ADB command or device precondition fails."""


@dataclass
class ShellResult:
    returncode: int
    stdout: str
    stderr: str


@dataclass
class RemoteStream:
    proc: subprocess.Popen
    stdout_file: IO[bytes]
    local_path: Path
    remote_pid_file: Optional[str] = None
    remote_stop_file: Optional[str] = None


def _normalize_script(script: str) -> bytes:
    """Normalize line endings to LF so Windows CRLF never breaks Android sh."""
    normalized = script.replace("\r\n", "\n").replace("\r", "\n")
    if not normalized.endswith("\n"):
        normalized += "\n"
    return normalized.encode("utf-8")


def _decode_output(data: bytes | str | None) -> str:
    if data is None:
        return ""
    if isinstance(data, bytes):
        text = data.decode("utf-8", errors="replace")
    else:
        text = data
    return text.replace("\r\n", "\n")


class AdbClient:
    """Thin wrapper over adb CLI for root shell, file transfer, and background streams."""

    def __init__(self, serial: Optional[str] = None) -> None:
        self.serial = self.resolve_serial(serial)

    @staticmethod
    def list_connected_devices() -> list[str]:
        res = subprocess.run(
            ["adb", "devices"],
            capture_output=True,
            timeout=10.0,
        )
        text = _decode_output(res.stdout)
        devices: list[str] = []
        for line in text.splitlines()[1:]:
            parts = line.strip().split()
            if len(parts) >= 2 and parts[1] == "device":
                devices.append(parts[0])
        return devices

    @classmethod
    def resolve_serial(cls, serial: Optional[str]) -> str:
        devices = cls.list_connected_devices()
        if serial:
            if serial not in devices:
                raise AdbError(
                    f"Device '{serial}' is not online. Connected devices: {devices}"
                )
            return serial
        if len(devices) == 1:
            return devices[0]
        if not devices:
            raise AdbError("No ADB devices connected in 'device' state.")
        raise AdbError(
            f"Multiple ADB devices connected ({devices}); pass -s/--serial explicitly."
        )

    def _base_cmd(self) -> list[str]:
        return ["adb", "-s", self.serial]

    def run_cmd(self, args: list[str], timeout: float = 30.0, check: bool = False) -> ShellResult:
        res = subprocess.run(
            self._base_cmd() + args,
            capture_output=True,
            timeout=timeout,
        )
        result = ShellResult(
            returncode=res.returncode,
            stdout=_decode_output(res.stdout),
            stderr=_decode_output(res.stderr),
        )
        if check and result.returncode != 0:
            raise AdbError(
                f"ADB command {args} failed ({result.returncode}): {result.stderr.strip() or result.stdout.strip()}"
            )
        return result

    def run_shell(
        self,
        script: str,
        timeout: float = 30.0,
        check: bool = False,
    ) -> ShellResult:
        """Execute a multi-line POSIX sh script safely via stdin."""
        res = subprocess.run(
            self._base_cmd() + ["shell", "sh"],
            input=_normalize_script(script),
            capture_output=True,
            timeout=timeout,
        )
        result = ShellResult(
            returncode=res.returncode,
            stdout=_decode_output(res.stdout),
            stderr=_decode_output(res.stderr),
        )
        if check and result.returncode != 0:
            snippet = script.strip().splitlines()[0] if script.strip() else ""
            raise AdbError(
                f"Shell script failed ({result.returncode}) [{snippet}]: "
                f"{result.stderr.strip() or result.stdout.strip()}"
            )
        return result

    def start_stream(
        self,
        script: str,
        local_stdout_path: Path,
        remote_pid_file: Optional[str] = None,
        remote_stop_file: Optional[str] = None,
    ) -> RemoteStream:
        """Start a long-running remote stream whose stdout writes directly to local_stdout_path."""
        local_stdout_path.parent.mkdir(parents=True, exist_ok=True)
        fh = open(local_stdout_path, "wb")
        proc = subprocess.Popen(
            self._base_cmd() + ["shell", "sh"],
            stdin=subprocess.PIPE,
            stdout=fh,
            stderr=subprocess.STDOUT,
        )
        assert proc.stdin is not None
        proc.stdin.write(_normalize_script(script))
        proc.stdin.flush()
        proc.stdin.close()
        return RemoteStream(
            proc=proc,
            stdout_file=fh,
            local_path=local_stdout_path,
            remote_pid_file=remote_pid_file,
            remote_stop_file=remote_stop_file,
        )

    def stop_stream(self, stream: RemoteStream, timeout: float = 6.0) -> None:
        """Gracefully signal remote collector to exit and flush local file handle."""
        try:
            if stream.remote_stop_file:
                self.run_shell(
                    f"touch {shlex.quote(stream.remote_stop_file)}",
                    timeout=5.0,
                )
            if stream.remote_pid_file:
                self.run_shell(
                    f"""
                    if [ -f {shlex.quote(stream.remote_pid_file)} ]; then
                      p=$(cat {shlex.quote(stream.remote_pid_file)} 2>/dev/null)
                      if [ -n "$p" ]; then
                        kill -TERM "$p" 2>/dev/null || true
                      fi
                    fi
                    """,
                    timeout=5.0,
                )
            try:
                stream.proc.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                if stream.remote_pid_file:
                    self.run_shell(
                        f"""
                        if [ -f {shlex.quote(stream.remote_pid_file)} ]; then
                          p=$(cat {shlex.quote(stream.remote_pid_file)} 2>/dev/null)
                          if [ -n "$p" ]; then
                            kill -KILL "$p" 2>/dev/null || true
                          fi
                        fi
                        """,
                        timeout=5.0,
                    )
                stream.proc.terminate()
                try:
                    stream.proc.wait(timeout=2.0)
                except subprocess.TimeoutExpired:
                    stream.proc.kill()
                    stream.proc.wait(timeout=2.0)
            cleanup_paths = [
                p for p in (stream.remote_pid_file, stream.remote_stop_file) if p
            ]
            if cleanup_paths:
                self.rm_remote(*cleanup_paths)
        finally:
            try:
                stream.stdout_file.flush()
            finally:
                stream.stdout_file.close()

    def pull(self, remote_path: str, local_path: Path, timeout: float = 120.0) -> None:
        local_path.parent.mkdir(parents=True, exist_ok=True)
        self.run_cmd(["pull", remote_path, str(local_path)], timeout=timeout, check=True)

    def push(self, local_path: Path, remote_path: str, timeout: float = 30.0) -> None:
        self.run_cmd(["push", str(local_path), remote_path], timeout=timeout, check=True)

    def rm_remote(self, *paths: str) -> None:
        quoted = " ".join(shlex.quote(p) for p in paths if p)
        if quoted:
            self.run_shell(f"rm -f {quoted}", timeout=10.0)

    def check_root(self) -> tuple[bool, str]:
        res = self.run_shell("id", timeout=5.0, check=True)
        out = res.stdout.strip()
        return ("uid=0(" in out, out)

    def get_screen_size(self) -> tuple[int, int]:
        res = self.run_shell("wm size", timeout=5.0, check=True)
        # Prefer Override size if present, else Physical size.
        override = re.search(r"Override size:\s*(\d+)x(\d+)", res.stdout)
        if override:
            return int(override.group(1)), int(override.group(2))
        phys = re.search(r"Physical size:\s*(\d+)x(\d+)", res.stdout)
        if phys:
            return int(phys.group(1)), int(phys.group(2))
        raise AdbError(f"Could not parse screen size from: {res.stdout!r}")

    def get_pid(self, package: str) -> Optional[int]:
        res = self.run_shell(f"pidof {shlex.quote(package)}", timeout=5.0)
        tokens = res.stdout.strip().split()
        if not tokens:
            return None
        try:
            return int(tokens[0])
        except ValueError:
            return None

    def launch_package(self, package: str) -> str:
        """Wake screen if asleep and bring target package to foreground cleanly."""
        script = f"""
        PKG={shlex.quote(package)}
        wake=$(dumpsys power 2>/dev/null | grep -m1 "mWakefulness=" | tr -d '\r')
        case "$wake" in
          *Awake*) ;;
          *) input keyevent KEYCODE_WAKEUP >/dev/null 2>&1; sleep 0.3; wm dismiss-keyguard >/dev/null 2>&1 || true ;;
        esac
        if am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1; then
          echo "launched:$PKG/.MainActivity"
          exit 0
        fi
        if am start -W -a android.intent.action.MAIN -c de.robv.android.xposed.category.MODULE_SETTINGS "$PKG" >/dev/null 2>&1; then
          echo "launched:MODULE_SETTINGS"
          exit 0
        fi
        monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
        echo "launched:monkey"
        """
        res = self.run_shell(script, timeout=15.0)
        return res.stdout.strip() or "unknown"

    def emit_sync_anchor(
        self,
        tag: str,
        ftrace_instance: Optional[str] = None,
    ) -> dict[str, object]:
        """Emit synchronized clock anchor across /proc/uptime, ftrace trace_marker, and logcat."""
        inst_path = (
            f"/sys/kernel/tracing/instances/{ftrace_instance}/trace_marker"
            if ftrace_instance
            else ""
        )
        script = f"""
        u0=$(cut -d' ' -f1 /proc/uptime)
        log -p i -t ZToolPerfAudit {shlex.quote(tag)}
        if [ -n {shlex.quote(inst_path)} ] && [ -w {shlex.quote(inst_path)} ]; then
          printf 'I|0|%s\\n' {shlex.quote(tag)} > {shlex.quote(inst_path)}
        fi
        u1=$(cut -d' ' -f1 /proc/uptime)
        echo "U0=$u0"
        echo "U1=$u1"
        """
        host_utc = time.time()
        res = self.run_shell(script, timeout=5.0, check=True)
        u0 = u1 = None
        for line in res.stdout.splitlines():
            if line.startswith("U0="):
                u0 = float(line[3:].strip())
            elif line.startswith("U1="):
                u1 = float(line[3:].strip())
        mid = ((u0 + u1) / 2.0) if (u0 is not None and u1 is not None) else None
        return {
            "tag": tag,
            "host_epoch_s": host_utc,
            "uptime_before_s": u0,
            "uptime_after_s": u1,
            "uptime_mid_s": mid,
        }
