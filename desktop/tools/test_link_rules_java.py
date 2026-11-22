#!/usr/bin/env python3
"""P1 review (M4): the placeholder locator passes the production Java LinkRecord validation, on a host JVM with pinned AOSP org.json."""
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
    sources = [app / 'LinkBudget.java', app / 'LinkRecord.java', app / 'ArchiveJson.java', root / 'desktop/tools/java/LinkRulesHost.java']
    compiler = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    with tempfile.TemporaryDirectory() as out:
        subprocess.run([*compiler, '-cp', str(args.json_classes), '-d', out, *map(str, sources)], check=True)
        subprocess.run(['java', '-cp', f'{out}:{args.json_classes.resolve()}', 'org.stackdocs.app.LinkRulesHost'], check=True)


if __name__ == '__main__':
    main()
