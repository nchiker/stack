#!/usr/bin/env python3
"""Run synthetic-only native window captures with isolated XDG state and bounded timeouts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    root = Path(__file__).resolve().parents[1]
    parser.add_argument("--binary", type=Path, default=root / "target/debug/stack-desktop")
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeat", type=int, default=0, help="Extra shutdown cycles, 0–20")
    args = parser.parse_args()
    if not 0 <= args.repeat <= 20:
        parser.error("Repeat must be 0–20")
    os.umask(0o077)
    args.output.mkdir(mode=0o700)
    env = dict(os.environ)
    for name in ("DATA", "CONFIG", "CACHE", "STATE"):
        directory = args.output / "xdg" / name.lower()
        directory.mkdir(parents=True)
        env[f"XDG_{name}_HOME"] = str(directory.absolute())
    env.setdefault("STACK_PDFIUM_LIBRARY", str(root / "runtime/pdfium/lib/libpdfium.so"))
    binary = args.binary.absolute()
    cases = [("light", "light", "1080x780", "1"),
             ("dark", "dark", "1080x780", "1"),
             ("narrow", "light", "680x680", "1"),
             ("large-text", "dark", "680x680", "2")]
    cases += [(f"shutdown-{index + 1:02}", "light", "1080x780", "1") for index in range(args.repeat)]
    report = {"binary_sha256": digest(binary), "engine_sha256": digest(Path(env["STACK_PDFIUM_LIBRARY"])),
              "synthetic_only": True, "cases": []}
    for label, theme, size, scale in cases:
        command = [str(binary), "--demo", str(args.fixtures.absolute()), "--smoke-output",
                   str((args.output / label).absolute()), "--theme", theme,
                   "--size", size, "--text-scale", scale]
        with (args.output / f"{label}.log").open("w") as log:
            try:
                completed = subprocess.run(command, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=35)
                status = completed.returncode
            except subprocess.TimeoutExpired:
                status = "timeout"
        case = {"name": label, "theme": theme, "requested_size": size,
                "content_text_scale": scale, "exit_status": status, "captures": []}
        for name in ("01-report-page-1.png", "02-report-page-2.png", "03-oriented-image.png"):
            path = args.output / label / name
            if path.is_file():
                header = path.read_bytes()[:24]
                if header[:8] != b"\x89PNG\r\n\x1a\n":
                    raise SystemExit("Invalid native capture")
                width, height = struct.unpack(">II", header[16:24])
                case["captures"].append({"name": name, "width": width, "height": height, "sha256": digest(path)})
        report["cases"].append(case)
        (args.output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        if status != 0 or len(case["captures"]) != 3:
            raise SystemExit(f"FAIL {label}: exit {status}; see own-process log in output directory")
        print(f"PASS {label}", flush=True)


if __name__ == "__main__":
    main()
