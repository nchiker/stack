#!/usr/bin/env python3
"""D4a: run the production Java HelloGate (hello parsing, ladder, skip rule) on a host JVM."""
import argparse
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json-classes', required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    app = root / 'app/src/main/java/org/stackdocs/app'
    sources = [app / n for n in ('ArchiveJson.java', 'SyncProtocol.java', 'HelloGate.java')]
    sources.append(root / 'desktop/tools/java/HelloGateHost.java')
    compiler = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    with tempfile.TemporaryDirectory() as out:
        subprocess.run([*compiler, '-cp', str(args.json_classes), '-d', out, *map(str, sources)], check=True)
        subprocess.run(['java', f'-Dpairing.source={app / "PairingTransport.java"}', '-cp', f'{out}:{args.json_classes.resolve()}',
                        'org.stackdocs.app.HelloGateHost'], check=True)


if __name__ == '__main__':
    main()
