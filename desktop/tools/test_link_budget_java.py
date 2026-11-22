#!/usr/bin/env python3
"""P1: run the production Java LinkBudget (inclusion order, distinct-blob meter, UTF-8 length, presence mapping) on a host JVM."""
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    root = Path(__file__).resolve().parents[2]
    app = root / 'app/src/main/java/org/stackdocs/app'
    sources = [app / 'LinkBudget.java', root / 'desktop/tools/java/LinkBudgetHost.java']
    compiler = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    with tempfile.TemporaryDirectory() as out:
        subprocess.run([*compiler, '-d', out, *map(str, sources)], check=True)
        subprocess.run(['java', '-cp', out, 'org.stackdocs.app.LinkBudgetHost'], check=True)


if __name__ == '__main__':
    main()
