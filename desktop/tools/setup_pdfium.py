#!/usr/bin/env python3
"""Install the pinned development renderer and all notices; never runs in the app."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import platform
import shutil
import tarfile
import tempfile
import urllib.request

BUILD = "chromium/8100"
URL = "https://github.com/bblanchon/pdfium-binaries/releases/download/chromium/8100/pdfium-linux-x64.tgz"
ARCHIVE_SHA256 = "1c1894a12d4d96ee01400c3874444126254180c5c7d2ab628576b67f8120320a"
LIBRARY_SHA256 = "b8784cbd652ad2993332b7695e2c80f3a797f877fc47d9be97b66bca9639539e"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Use an already downloaded archive, without network")
    parser.add_argument("--destination", type=Path, default=Path(__file__).resolve().parents[1] / "runtime/pdfium")
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
        parser.error("This M0 renderer pin supports Linux x86-64 only")
    destination = args.destination.absolute()
    if destination.exists():
        raise SystemExit("Destination already exists; choose a new directory rather than overwriting it")
    if args.archive:
        with args.archive.open("rb") as source:
            data = source.read(16 * 1024 * 1024 + 1)
    else:
        with urllib.request.urlopen(URL, timeout=60) as source:
            data = source.read(16 * 1024 * 1024 + 1)
    if len(data) > 16 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != ARCHIVE_SHA256:
        raise SystemExit("PDFium archive length or SHA-256 verification failed")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".pdfium-stage-", dir=destination.parent) as stage_name:
        stage = Path(stage_name)
        hashes = {}
        total = 0
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            for member in archive.getmembers():
                path = PurePosixPath(member.name)
                if path.is_absolute() or ".." in path.parts or member.issym() or member.islnk():
                    raise SystemExit("Unsafe PDFium archive entry")
                if member.isdir():
                    continue
                if not member.isfile():
                    raise SystemExit("Unsupported PDFium archive entry")
                total += member.size
                if member.size > 16 * 1024 * 1024 or total > 32 * 1024 * 1024:
                    raise SystemExit("PDFium extraction limit exceeded")
                if member.name not in ("LICENSE", "VERSION", "args.gn", "lib/libpdfium.so") and path.parts[0] != "licenses":
                    continue
                target = stage / path
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(member) as source, target.open("xb") as output:
                    shutil.copyfileobj(source, output, 64 * 1024)
                hashes[member.name] = hashlib.sha256(target.read_bytes()).hexdigest()
        if hashes.get("lib/libpdfium.so") != LIBRARY_SHA256 or "licenses/pdfium.txt" not in hashes:
            raise SystemExit("PDFium library or notices verification failed")
        receipt = {"build": BUILD, "url": URL, "archive_sha256": ARCHIVE_SHA256,
                   "platform": "linux-x64", "files": hashes}
        (stage / "ENGINE.json").write_text(json.dumps(receipt, indent=2) + "\n")
        os.rename(stage, destination)
    print(f"Verified PDFium {BUILD} and {len(hashes)} runtime/notice files installed.")
    print(f"STACK_PDFIUM_LIBRARY={destination / 'lib/libpdfium.so'}")


if __name__ == "__main__":
    main()
