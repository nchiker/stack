#!/usr/bin/env python3
"""P2: run the production Java LinkResolve rule (one merge rule per link: gone, later modified, same hash, modified tie) on a host JVM."""
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parents[2]
    app = root / 'app/src/main/java/org/stackdocs/app'
    sources = [app / 'LinkResolve.java', root / 'desktop/tools/java/LinkResolveHost.java']
    compiler = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    with tempfile.TemporaryDirectory() as out:
        subprocess.run([*compiler, '-d', out, *map(str, sources)], check=True)
        subprocess.run(['java', '-cp', out, 'org.stackdocs.app.LinkResolveHost'], check=True)


if __name__ == '__main__':
    main()
