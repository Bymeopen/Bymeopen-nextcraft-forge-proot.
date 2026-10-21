import os
import shutil
import subprocess
import sys

import pytest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
PACKAGE_PARENT = os.path.join(ROOT, "app", "src", "main", "assets", "rea")
sys.path.insert(0, PACKAGE_PARENT)

HELLO_C = r'''
#include <stdio.h>
int main(void) { puts("hello rea"); return 0; }
'''


@pytest.fixture(scope="session")
def hello_elf(tmp_path_factory):
    """Program C kecil yang dikompilasi di mesin uji (dilewati bila tidak ada kompilator)."""
    compiler = shutil.which("gcc") or shutil.which("cc")
    if not compiler:
        pytest.skip("tidak ada kompilator C")
    directory = tmp_path_factory.mktemp("fixtures")
    source = directory / "hello.c"
    source.write_text(HELLO_C)
    binary = directory / "hello"
    subprocess.run([compiler, "-o", str(binary), str(source)], check=True)
    return str(binary)


@pytest.fixture()
def run_rea():
    """Menjalankan ``python -m rea`` sebagai proses terpisah, seperti aplikasi."""

    def run(*args, cwd=None):
        env = dict(os.environ, PYTHONPATH=PACKAGE_PARENT, HOME=str(cwd or ROOT))
        return subprocess.run(
            [sys.executable, "-m", "rea", *args], capture_output=True, text=True, env=env, cwd=cwd, timeout=60
        )

    return run
