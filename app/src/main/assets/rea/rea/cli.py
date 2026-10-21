"""Antarmuka perintah ``rea``. Pesan manusia ke stderr; dengan ``--json`` satu objek JSON di baris terakhir stdout."""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Sequence

from . import __version__
from .analysis import cfg as cfg_mod
from .analysis import disasm as disasm_mod
from .analysis import entropy as entropy_mod
from .analysis import strings as strings_mod
from .analysis import xrefs as xrefs_mod
from .analysis.code import decode, defined_functions
from .capability import FAIL, OK, WARN, run_doctor
from .identify import identify
from .parsers import elf as elf_mod
from .report.markdown import build_report
from .result import E_BAD_FORMAT, E_INTERNAL, E_USAGE, ReaError, Result, emit_json, failure

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


def _parse_address(text: str) -> int:
    try:
        return int(text, 0)
    except ValueError as error:
        raise ReaError(E_USAGE, f"Alamat tidak valid: {text}", "Contoh: --addr 0x401136") from error


def _cfg_for(path: str, symbol: str | None) -> dict:
    """CFG da fungsi ``symbol`` (default ``main``, atau titik masuk bila biner tidak punya simbol)."""
    from .parsers.elf import function_symbols, open_elf
    from .result import E_SYMBOL

    with open(path, "rb") as handle:
        elf = open_elf(handle)
        functions = function_symbols(elf)
        wanted = symbol or "main"
        match = next((f for f in functions if f["name"] == wanted), None)
        if match is not None:
            code = decode(elf, handle, function=match)
        elif not symbol:
            code = decode(elf, handle, address=elf.header["e_entry"], name="entry")
        else:
            raise ReaError(E_SYMBOL, f"Simbol tidak ditemukan: {symbol}", "Biner mungkin di-strip; pakai `rea analyze --disasm` tanpa nama untuk titik masuk.")
    return cfg_mod.build_cfg(code)


def _analyze(args: argparse.Namespace) -> Result:
    info = identify(args.file)
    data: dict = {"identify": info}
    result = Result(command="analyze", data=data)
    is_elf = info["type"] == "ELF"
    if is_elf:
        data["elf"] = elf_mod.summarize(args.file)
    elif args.disasm is not None or args.addr is not None:
        raise ReaError(E_BAD_FORMAT, f"Disassembly belum mendukung berkas {info['type']}.", "Saat ini hanya ELF (fase 2).")
    else:
        result.warnings.append(f"Parser untuk {info['type']} belum tersedia; hanya identifikasi dan string.")
    needs_elf = args.cfg is not None or args.xrefs
    if needs_elf and not is_elf:
        raise ReaError(E_BAD_FORMAT, f"CFG dan xref belum mendukung berkas {info['type']}.", "Saat ini hanya ELF.")
    if args.strings:
        data["strings"] = strings_mod.extract(
            args.file, min_len=args.min_len, max_items=args.max_strings, only_interesting=args.interesting
        )
    if args.entropy:
        data["entropy"] = {
            "map": entropy_mod.entropy_map(args.file),
            "sections": entropy_mod.section_entropy(args.file, data["elf"]["sections"]) if is_elf else [],
        }
    if args.cfg is not None:
        graph = _cfg_for(args.file, args.cfg or None)
        if not args.dot:
            graph.pop("dot", None)
        data["cfg"] = graph
    if args.xrefs:
        pool = strings_mod.extract(args.file, max_items=3000)["strings"]
        data["xrefs"] = xrefs_mod.analyze_xrefs(args.file, pool)
        result.warnings += data["xrefs"].pop("warnings")
    if args.disasm is not None or args.addr is not None:
        address = _parse_address(args.addr) if args.addr is not None else None
        symbol = None if address is not None or args.disasm == "" else args.disasm
        data["disasm"] = disasm_mod.disassemble(args.file, symbol=symbol, address=address, count=args.count)
    if not args.json:
        _print_analysis(data)
    return result


def _report(args: argparse.Namespace) -> Result:
    """Laporan Markdown: identifikasi, ringkasan ELF, string menarik, dan disassembly fungsi utama."""
    info = identify(args.file)
    data: dict = {"identify": info, "missing": []}
    if info["type"] == "ELF":
        data["elf"] = elf_mod.summarize(args.file)
        try:
            symbol = "main" if any(f["name"] == "main" for f in data["elf"]["functions"]) else None
            data["disasm"] = disasm_mod.disassemble(args.file, symbol=symbol, count=40)
        except ReaError as error:
            data["missing"].append(f"Disassembly dilewati: {error.message}")
    else:
        data["missing"].append(f"Parser untuk {info['type']} belum tersedia; laporan hanya berisi identifikasi dan string.")
    data["strings"] = strings_mod.extract(args.file, max_items=500)
    data["entropy"] = {
        "map": entropy_mod.entropy_map(args.file),
        "sections": entropy_mod.section_entropy(args.file, data["elf"]["sections"]) if info["type"] == "ELF" else [],
    }
    if info["type"] == "ELF":
        try:
            data["cfg"] = _cfg_for(args.file, None)
            data["xrefs"] = xrefs_mod.analyze_xrefs(args.file, strings_mod.extract(args.file, max_items=3000)["strings"])
        except ReaError as error:
            data["missing"].append(f"CFG dan xref dilewati: {error.message}")
    text = build_report(data, notes=args.notes or "")
    out = args.output or os.path.join(".rea", os.path.basename(args.file) + ".report.md")
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    with open(out, "w", encoding="utf-8") as handle:
        handle.write(text)
    if not args.json:
        print(f"Laporan ditulis ke {out}", file=sys.stderr)
    return Result(command="report", data={"path": out, "bytes": len(text.encode("utf-8")), "sections": text.count("\n## ")})


def _print_analysis(data: dict) -> None:
    info = data["identify"]
    print(f"{info['path']}  {info['type']} {info.get('arch', '')} {info.get('bits', '')}  {info['size']} bayt", file=sys.stderr)
    elf = data.get("elf")
    if elf:
        print(f"  tipe {elf['type']}, mesin {elf['machine']}, entry 0x{elf['entry']:x}, "
              f"{'di-strip' if elf['stripped'] else 'ada simbol'}", file=sys.stderr)
        if elf["needed"]:
            print("  bergantung pada: " + ", ".join(elf["needed"]), file=sys.stderr)
        print(f"  {len(elf['sections'])} section, {len(elf['segments'])} segment, {len(elf['functions'])} fungsi", file=sys.stderr)
    found = data.get("strings")
    if found:
        print(f"\n{len(found['strings'])} string (total {found['total']}):", file=sys.stderr)
        for item in found["strings"]:
            print(f"  {item['offset']:08x} [{item['score']}] {item['text']}", file=sys.stderr)
    graph = data.get("cfg")
    if graph:
        print(f"\nCFG {graph['function']} ({len(graph['blocks'])} blok, {graph['back_edges']} back-edge):", file=sys.stderr)
        print(graph["ascii"], file=sys.stderr)
        if graph.get("dot"):
            print(graph["dot"], file=sys.stderr)
    xr = data.get("xrefs")
    if xr:
        print(f"\nCall graph ({xr['functions_scanned']} fungsi):", file=sys.stderr)
        for caller, callees in xr["call_graph"].items():
            print(f"  {caller} -> {', '.join(callees)}", file=sys.stderr)
        for row in xr["strings"]:
            print(f"  \"{row['text'][:60]}\" dipakai oleh " + ", ".join(sorted({r['function'] for r in row['refs']})), file=sys.stderr)
    ent = data.get("entropy")
    if ent:
        print(f"\nEntropi {ent['map']['spark']}  (blok tinggi: {ent['map']['high_blocks']})", file=sys.stderr)
        for row in ent["sections"]:
            print(f"  {row['name']:<20} {row['entropy']:.2f}{'  ⚠️ tinggi' if row['high'] else ''}", file=sys.stderr)
    code = data.get("disasm")
    if code:
        print(f"\n{code['function'] or '?'} @ 0x{code['address']:x}:", file=sys.stderr)
        for insn in code["instructions"]:
            print(f"  {insn['addr']:x}:  {insn['bytes']:<20} {insn['mnemonic']} {insn['operands']}", file=sys.stderr)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="rea", description="Reverse Engine Anything")
    parser.add_argument("--version", action="version", version=f"rea {__version__}")
    sub = parser.add_subparsers(dest="command", required=True)
    for name, helptext in (
        ("doctor", "cek kemampuan lingkungan"),
        ("id", "identifikasi berkas"),
        ("analyze", "analisis statis berkas ELF"),
        ("report", "laporan Markdown dari analisis"),
    ):
        command = sub.add_parser(name, help=helptext)
        command.add_argument("--json", action="store_true", help="keluaran JSON di baris terakhir stdout")
        if name in ("id", "analyze", "report"):
            command.add_argument("file", help="berkas yang diperiksa")
        if name == "analyze":
            command.add_argument("--strings", action="store_true", help="ekstrak string ASCII dan UTF-16")
            command.add_argument("--min-len", type=int, default=4, help="panjang minimum string (default 4)")
            command.add_argument("--max-strings", type=int, default=500, help="jumlah string maksimum (default 500)")
            command.add_argument("--interesting", action="store_true", help="hanya string yang menarik (URL, jalur, kunci)")
            command.add_argument("--disasm", nargs="?", const="", metavar="FUNGSI", help="disassembly fungsi (tanpa nama: titik masuk)")
            command.add_argument("--addr", metavar="ALAMAT", help="disassembly mulai dari alamat virtual (mis. 0x401136)")
            command.add_argument("--count", type=int, default=64, help="jumlah instruksi maksimum (default 64)")
            command.add_argument("--cfg", nargs="?", const="", metavar="FUNGSI", help="control flow graph (default main)")
            command.add_argument("--dot", action="store_true", help="sertakan keluaran Graphviz DOT untuk --cfg")
            command.add_argument("--xrefs", action="store_true", help="call graph dan fungsi yang memakai tiap string")
            command.add_argument("--entropy", action="store_true", help="peta entropi per blok dan per section")
        if name == "report":
            command.add_argument("-o", "--output", help="berkas keluaran (default .rea/<nama>.report.md)")
            command.add_argument("--notes", help="catatan analis yang disisipkan di laporan")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    try:
        args = parser.parse_args(argv)
    except SystemExit as exit_request:
        # argparse sudah mencetak pesan ke stderr; --version dan --help keluar dengan 0.
        return int(exit_request.code or 0) if isinstance(exit_request.code, int) else 2
    handlers = {"doctor": _doctor, "id": _id, "analyze": _analyze, "report": _report}
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
