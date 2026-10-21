"""Antarmuka perintah ``rea``. Pesan manusia ke stderr; dengan ``--json`` satu objek JSON di baris terakhir stdout."""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Sequence

from . import __version__
from .capability import FAIL, OK, WARN, run_doctor
from .identify import identify
from .result import E_INTERNAL, E_USAGE, ReaError, Result, emit_json, failure

_NOTICE = (
    "REA hanya untuk penggunaan yang sah: software milik sendiri, CTF, crackme, interoperabilitas, riset keamanan "
    "berizin, dan analisis malware defensif. Jangan memakainya untuk melanggar lisensi atau menyerang sistem tanpa izin."
)
_SYMBOL = {OK: "✅", WARN: "⚠️ ", FAIL: "❌"}


def _consent_path() -> str:
    return os.path.join(os.path.expanduser("~"), ".rea", "consent.json")


def _show_notice_once() -> None:
    """Pemberitahuan penggunaan sah di pemakaian pertama; dicatat agar tidak diulang."""
    path = _consent_path()
    if os.path.exists(path):
        return
    print(_NOTICE, file=sys.stderr)
    try:
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as handle:
            json.dump({"notice_shown": True, "version": __version__}, handle)
    except OSError:
        pass  # Tidak bisa menulis HOME: pemberitahuan akan tampil lagi, itu tidak berbahaya.


def _doctor(args: argparse.Namespace) -> Result:
    mode, checks = run_doctor(os.getcwd())
    result = Result(command="doctor", mode=mode, data={"checks": [c.as_dict() for c in checks], "version": __version__})
    if not args.json:
        print(f"REA {__version__}  mode: {mode}", file=sys.stderr)
        for check in checks:
            line = f"{_SYMBOL[check.status]} {check.label}: {check.detail}"
            print(line + (f"  → {check.hint}" if check.hint and check.status != OK else ""), file=sys.stderr)
    result.warnings = [f"{c.label}: {c.hint}" for c in checks if c.status == FAIL and c.hint][:5]
    return result


def _id(args: argparse.Namespace) -> Result:
    info = identify(args.file)
    if not args.json:
        for key in ("path", "type", "arch", "bits", "endian", "size", "md5", "sha256", "entropy"):
            if key in info:
                print(f"{key:8} {info[key]}", file=sys.stderr)
        if info.get("packed_hint"):
            print("⚠️  Entropi tinggi: berkas mungkin dipak atau dienkripsi.", file=sys.stderr)
    return Result(command="id", data=info)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="rea", description="Reverse Engine Anything")
    parser.add_argument("--version", action="version", version=f"rea {__version__}")
    sub = parser.add_subparsers(dest="command", required=True)
    for name, helptext in (("doctor", "cek kemampuan lingkungan"), ("id", "identifikasi berkas")):
        command = sub.add_parser(name, help=helptext)
        command.add_argument("--json", action="store_true", help="keluaran JSON di baris terakhir stdout")
        if name == "id":
            command.add_argument("file", help="berkas yang diidentifikasi")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    try:
        args = parser.parse_args(argv)
    except SystemExit as exit_request:
        # argparse sudah mencetak pesan ke stderr; --version dan --help keluar dengan 0.
        return int(exit_request.code or 0) if isinstance(exit_request.code, int) else 2
    handlers = {"doctor": _doctor, "id": _id}
    _show_notice_once()
    try:
        result = handlers[args.command](args)
        code = 0
    except ReaError as error:
        result, code = failure(args.command, error), 1
        if not args.json:
            print(f"Galat: {error.message}" + (f"\n{error.hint}" if error.hint else ""), file=sys.stderr)
    except Exception as error:  # noqa: BLE001 - batas terluar: jangan pernah menumpahkan traceback ke aplikasi
        result = failure(args.command, ReaError(E_INTERNAL, f"{type(error).__name__}: {error}", "Laporkan galat ini."))
        code = 1
        if not args.json:
            print(f"Galat internal: {error}", file=sys.stderr)
    if args.json:
        emit_json(result)
    return code


if __name__ == "__main__":
    sys.exit(main())
