import json
import os
import re
import shutil
import subprocess

import pytest

pytest.importorskip("capstone")
pytest.importorskip("elftools")

from rea.analysis import disasm, strings  # noqa: E402
from rea.parsers import elf  # noqa: E402
from rea.result import ReaError  # noqa: E402

SOURCE = r'''
#include <stdio.h>
int helper(int a) { return a * 3 + 1; }
int main(void) {
    puts("hello rea");
    puts("https://example.com/api/v1");
    return helper(2);
}
'''


@pytest.fixture(scope="module")
def sample(tmp_path_factory):
    compiler = shutil.which("gcc") or shutil.which("cc")
    if not compiler:
        pytest.skip("tidak ada kompilator C")
    directory = tmp_path_factory.mktemp("analyze")
    (directory / "t.c").write_text(SOURCE)
    # -O0 -fno-pie ... bukan perlu; default sudah cukup. Tanpa -s agar simbol ada.
    subprocess.run([compiler, "-O0", "-o", str(directory / "t"), str(directory / "t.c")], check=True)
    return str(directory / "t")


def test_elf_summary(sample):
    summary = elf.summarize(sample)
    assert summary["class"] in (32, 64)
    assert summary["stripped"] is False
    assert any(f["name"] == "main" for f in summary["functions"])
    assert any(f["name"] == "helper" for f in summary["functions"])
    assert any(s["name"] == ".text" and "X" in s["flags"] for s in summary["sections"])
    assert any(seg["type"] == "LOAD" for seg in summary["segments"])
    assert any(lib.startswith("libc") for lib in summary["needed"])
    assert "puts" in "".join(summary["imports"])


def test_strings_found_with_offsets_and_scores(sample):
    result = strings.extract(sample)
    texts = {item["text"]: item for item in result["strings"]}
    assert "hello rea" in texts
    assert texts["https://example.com/api/v1"]["score"] >= 5
    with open(sample, "rb") as handle:
        data = handle.read()
    assert data[texts["hello rea"]["offset"]:].startswith(b"hello rea")


def test_interesting_filter_keeps_urls_not_symbol_versions(sample):
    texts = [item["text"] for item in strings.extract(sample, only_interesting=True)["strings"]]
    assert "https://example.com/api/v1" in texts
    assert not any("GLIBC_2" in t and "@" in t for t in texts), texts


def test_utf16_and_limits(tmp_path):
    path = tmp_path / "w.bin"
    path.write_bytes(b"\x00\x01" + "Secret Token".encode("utf-16-le") + b"\x00\x00" + b"plain ascii here")
    found = {item["text"]: item for item in strings.extract(str(path))["strings"]}
    assert found["Secret Token"]["encoding"] == "utf16le"
    assert found["plain ascii here"]["encoding"] == "ascii"
    assert found["Secret Token"]["score"] >= 3
    limited = strings.extract(str(path), max_items=1)
    assert limited["truncated"] is True and len(limited["strings"]) == 1 and limited["total"] == 2
    empty = tmp_path / "empty"
    empty.write_bytes(b"")
    assert strings.extract(str(empty))["strings"] == []


def _objdump_instructions(binary, name, count):
    objdump = shutil.which("objdump")
    if not objdump:
        pytest.skip("tidak ada objdump")
    text = subprocess.run([objdump, "-d", "-M", "intel", "--no-show-raw-insn", binary], capture_output=True, text=True, check=True).stdout
    block = text.split(f"<{name}>:")[1]
    rows = []
    for line in block.splitlines()[1:]:
        match = re.match(r"\s*([0-9a-f]+):\s+(\S+)", line)
        if match:
            rows.append((int(match.group(1), 16), match.group(2)))
        if len(rows) == count:
            break
    return rows


def test_main_disassembly_matches_objdump(sample):
    import platform

    if platform.machine() not in ("x86_64", "aarch64"):
        pytest.skip("arsitektur uji tidak dikenal")
    ours = disasm.disassemble(sample, symbol="main", count=5)
    assert ours["function"] == "main" and len(ours["instructions"]) == 5
    expected = _objdump_instructions(sample, "main", 5) if platform.machine() == "x86_64" else None
    if expected is not None:
        assert [(i["addr"], i["mnemonic"]) for i in ours["instructions"]] == expected
    # Alamat berurutan dan ukuran konsisten, apa pun arsitekturnya.
    addresses = [i["addr"] for i in ours["instructions"]]
    sizes = [i["size"] for i in ours["instructions"]]
    assert addresses[0] == ours["address"]
    assert all(addresses[i] + sizes[i] == addresses[i + 1] for i in range(len(addresses) - 1))


def test_disassembly_errors_and_entry(sample):
    with pytest.raises(ReaError) as missing:
        disasm.disassemble(sample, symbol="tidak_ada")
    assert missing.value.code == "symbol_not_found" and missing.value.hint
    entry = disasm.disassemble(sample, count=3)
    assert entry["function"] in ("entry", "_start") or entry["instructions"]
    with pytest.raises(ReaError) as bad:
        disasm.disassemble(sample, address=0x7FFFFFFF, count=3)
    assert bad.value.code == "bad_format"


def test_non_elf_is_rejected_cleanly(tmp_path):
    path = tmp_path / "notelf"
    path.write_bytes(b"MZ" + b"\x00" * 100)
    with pytest.raises(ReaError) as error:
        elf.summarize(str(path))
    assert error.value.code == "bad_format"


def test_cli_analyze_json(run_rea, tmp_path, sample):
    done = run_rea("analyze", sample, "--strings", "--interesting", "--disasm", "main", "--count", "4", "--json", cwd=tmp_path)
    assert done.returncode == 0, done.stderr
    result = json.loads([line for line in done.stdout.splitlines() if line.startswith("{")][-1])
    assert result["ok"] is True
    assert result["data"]["identify"]["type"] == "ELF"
    assert any(s["text"].startswith("https://") for s in result["data"]["strings"]["strings"])
    assert len(result["data"]["disasm"]["instructions"]) == 4

    bad = run_rea("analyze", sample, "--disasm", "tidak_ada", "--json", cwd=tmp_path)
    assert bad.returncode == 1
    error = json.loads([line for line in bad.stdout.splitlines() if line.startswith("{")][-1])["error"]
    assert error["code"] == "symbol_not_found"
