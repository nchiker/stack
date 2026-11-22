#!/usr/bin/env python3
"""Run unchanged production Java causal protocol against Rust's golden corpus."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import shutil


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json-classes', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--enforce-graph', action='store_true', help='accepted for compatibility; the graph_cases link checks always run (D3 landed)')
    parser.add_argument('--archive-classes', required=True, type=Path, help='java_archive_host classes (SyncGraph dependencies), see java_archive_host.py')
    args = parser.parse_args()
    args.output.mkdir(mode=0o700)
    root = Path(__file__).resolve().parents[2]
    sources = [root / 'app/src/main/java/org/stackdocs/app' / name for name in ('ArchiveJson.java', 'SyncProtocol.java')]
    sources.append(root / 'desktop/tools/java/SyncGolden.java')
    sources.append(root / 'app/src/main/java/org/stackdocs/app/SyncGraph.java')
    classpath = f'{args.json_classes}:{args.archive_classes.resolve()}'
    compiler = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    subprocess.run([*compiler, '-cp', classpath, '-d', str(args.output), *map(str, sources)], check=True)
    result = subprocess.check_output(['java', '-cp', f'{args.output}:{classpath}', 'org.stackdocs.app.SyncGolden', str(root / 'desktop/protocol/golden-v1.json')], text=True)
    report = json.loads(result)
    report['source_sha256'] = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    report['golden_sha256'] = hashlib.sha256((root / 'desktop/protocol/golden-v1.json').read_bytes()).hexdigest()
    report['qualification'] = 'Host JVM with pinned AOSP JSON classes; no Android database, renderer, lock or lifecycle evidence'
    (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
