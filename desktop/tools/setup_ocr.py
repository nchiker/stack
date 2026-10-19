#!/usr/bin/env python3
"""Package the pinned Ubuntu Linux x86-64 OCR assets offline; never run at launch.

Native dependencies other than Tesseract/Leptonica are provided by the target
Ubuntu 24.04 installation. ENGINE.json records the required distribution packages.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile

ENGINE_SHA = "0d3b71cd757860c6639918c1b2d9407c8652ee22babc8d5c5f18cc35dde6334b"
LEPT_SHA = "b31a50179665871ddb6bdbcfeefc8673febaa29a006e78136024d4083b6ee5e5"
MODEL_SHA = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, default=Path(__file__).resolve().parents[1] / "runtime/ocr")
    parser.add_argument("--library", type=Path, default=Path("/lib/x86_64-linux-gnu/libtesseract.so.5.0.3"))
    parser.add_argument("--leptonica", type=Path, default=Path("/lib/x86_64-linux-gnu/liblept.so.5.0.4"))
    args = parser.parse_args()
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "amd64"):
        parser.error("This pinned OCR runtime supports Ubuntu 24.04 x86-64 only")
    root = Path(__file__).resolve().parents[2]
    model = root / "app/src/main/assets/ocr/eng.traineddata"
    inputs = [(args.library, "lib/libtesseract.so.5", ENGINE_SHA),
              (args.leptonica, "lib/liblept.so.5", LEPT_SHA),
              (model, "tessdata/eng.traineddata", MODEL_SHA)]
    for source, _, expected in inputs:
        if not source.is_file() or hashlib.sha256(source.read_bytes()).hexdigest() != expected:
            raise SystemExit(f"Pinned OCR asset missing or checksum mismatch: {source}")
    destination = args.destination.absolute()
    if destination.exists():
        raise SystemExit("Destination already exists; choose a fresh directory")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".ocr-stage-", dir=destination.parent) as stage_name:
        stage = Path(stage_name)
        files = {}
        for source, target, digest in inputs:
            output = stage / target
            output.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, output)
            files[target] = digest
        notices = root / "app/src/main/assets/notices"
        for source, name in [(notices / "Tesseract-LICENSE.txt", "Tesseract-LICENSE.txt"),
                             (notices / "OCR-NOTICE.txt", "Android-model-provenance.txt"),
                             (Path("/usr/share/doc/libtesseract5/copyright"), "Tesseract-distribution-copyright.txt"),
                             (Path("/usr/share/doc/liblept5/copyright"), "Leptonica-distribution-copyright.txt")]:
            target = stage / "notices" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
            files[f"notices/{name}"] = hashlib.sha256(target.read_bytes()).hexdigest()
        packages = subprocess.check_output(["dpkg-query", "-W", "-f=${Package} ${Version}\n", "libtesseract5", "liblept5"], text=True).splitlines()
        receipt = {"engine": "Tesseract 5.3.4 C API", "language": "eng", "model_commit": "87416418657359cb625c412a48b6e1d6d41c29bd",
                   "platform": "Ubuntu 24.04 Linux x86-64", "packages": packages,
                   "system_dependencies": "Ubuntu 24.04 libtesseract5/liblept5 dependency ABI; packaged libraries do not constitute a standalone distribution",
                   "documentation": "https://tesseract-ocr.github.io/tessdoc/Data-Files.html", "files": files}
        (stage / "ENGINE.json").write_text(json.dumps(receipt, indent=2) + "\n")
        os.rename(stage, destination)
    print(f"STACK_TESSERACT_LIBRARY={destination / 'lib/libtesseract.so.5'}")
    print(f"STACK_TESSDATA_DIR={destination / 'tessdata'}")
    print(f"LD_LIBRARY_PATH={destination / 'lib'}")
    print("OMP_THREAD_LIMIT=1")


if __name__ == "__main__":
    main()
