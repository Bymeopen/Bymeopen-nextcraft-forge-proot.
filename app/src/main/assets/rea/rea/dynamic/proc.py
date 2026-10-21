"""Menjalankan alat dinamis (strace, gdb) sebagai proses anak dengan batas waktu dan keluaran dipotong."""

from __future__ import annotations

import os
import shutil
import signal
import subprocess
from dataclasses import dataclass

from ..result import E_MISSING_DEP, E_NOT_SUPPORTED, ReaError

MAX_OUTPUT = 64 * 1024
_PTRACE_DENIED = ("operation not permitted", "ptrace: ", "ptrace(ptrace_", "permission denied")


@dataclass
class Completed:
    code: int | None
    stdout: str
    stderr: str
    timed_out: bool


def require_tool(binary: str, package: str) -> str:
    path = shutil.which(binary)
    if path is None:
        raise ReaError(E_MISSING_DEP, f"{binary} belum terpasang.", f"Pasang: apt install {package}")
    return path


def run_limited(command: list[str], timeout: float, cwd: str | None = None) -> Completed:
    """Menjalankan ``command`` di grup proses sendiri; saat batas waktu habis seluruh grup dihentikan."""
    process = subprocess.Popen(
        command,
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        cwd=cwd,
        start_new_session=True,
    )
    timed_out = False
    try:
        out, err = process.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        out, err = process.communicate()
    return Completed(
        None if timed_out else process.returncode,
        out[:MAX_OUTPUT].decode("utf-8", "replace"),
        err[:MAX_OUTPUT].decode("utf-8", "replace"),
        timed_out,
    )


def raise_if_ptrace_denied(tool: str, text: str) -> None:
    """Kernel atau seccomp yang menolak ptrace dijelaskan dengan alternatifnya, bukan sebagai galat mentah."""
    lowered = text.lower()
    if "operation not permitted" in lowered and ("ptrace" in lowered or "seize" in lowered or "traceme" in lowered):
        raise ReaError(
            E_NOT_SUPPORTED,
            f"{tool} tidak bisa memakai ptrace di lingkungan ini (ditolak).",
            "Pakai --mode emu (QEMU gdbstub), yang tidak butuh ptrace; jalankan `rea doctor` untuk melihat mode yang tersedia.",
        )
