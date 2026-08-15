#!/usr/bin/env python3
"""Download Eclipse MAT (MemoryAnalyzer) into .tools/ — used by backend/mat.py
locally and by the CI workflow. Pinned version, same as the original local setup.

  python3 tools/get_mat.py            # download + unpack if missing, print ParseHeapDump.sh path
  MAT_HOME=/elsewhere python3 ...     # override the install root
"""
import os, re, shutil, sys, urllib.request, zipfile

MAT_VERSION = "1.17.0"
MAT_BUILD = "1.17.0.20260601"
ZIP_NAME = f"MemoryAnalyzer-{MAT_BUILD}-linux.gtk.x86_64.zip"
URL = f"https://download.eclipse.org/mat/{MAT_VERSION}/rcp/{ZIP_NAME}"

HERE = os.path.dirname(os.path.abspath(__file__))          # tools/
ROOT = os.path.dirname(HERE)                               # repo root
TOOLS = os.environ.get("MAT_HOME", os.path.join(ROOT, ".tools"))


def parse_sh():
    """Path to ParseHeapDump.sh when MAT is installed, else a path that simply
    does not exist yet (callers check os.path.exists)."""
    return os.path.join(TOOLS, "mat", "ParseHeapDump.sh")


def ensure(log=print):
    """Download and unpack MAT if missing. Returns the ParseHeapDump.sh path."""
    dst = parse_sh()
    if os.path.exists(dst):
        return dst
    os.makedirs(TOOLS, exist_ok=True)
    zpath = os.path.join(TOOLS, ZIP_NAME)
    if not zipfile.is_zipfile(zpath):
        log(f"downloading MAT {MAT_BUILD} ({URL}) ...")
        tmp = zpath + f".tmp{os.getpid()}"
        try:
            with urllib.request.urlopen(URL, timeout=60) as resp, open(tmp, "wb") as out:
                shutil.copyfileobj(resp, out, 1 << 20)
            if not zipfile.is_zipfile(tmp):
                raise RuntimeError(f"download from {URL} is not a zip — mirror trouble?")
            os.replace(tmp, zpath)
        finally:
            if os.path.exists(tmp):
                os.remove(tmp)
    log(f"unpacking {ZIP_NAME} -> {TOOLS}/ ...")
    with zipfile.ZipFile(zpath) as zf:
        zf.extractall(TOOLS)
        # zipfile.extractall drops unix permission bits — restore them from the
        # zip metadata or the MemoryAnalyzer binary won't be executable
        for zi in zf.infolist():
            mode = (zi.external_attr >> 16) & 0o777
            if mode:
                os.chmod(os.path.join(TOOLS, zi.filename), mode)
    if not os.path.exists(dst):
        raise RuntimeError(f"ParseHeapDump.sh not found after unpack of {zpath}")
    os.chmod(dst, 0o755)
    ini = os.path.join(TOOLS, "mat", "MemoryAnalyzer.ini")
    if os.path.exists(ini):
        # headless queries on a 16 GB box: 10g heap, same as the original local setup
        with open(ini) as f:
            txt = f.read()
        txt = re.sub(r"^-Xmx.*$", "-Xmx10g", txt, flags=re.M)
        with open(ini, "w") as f:
            f.write(txt)
    log(f"MAT ready: {dst}")
    return dst


if __name__ == "__main__":
    print(ensure())
