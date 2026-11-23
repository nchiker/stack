#!/usr/bin/env python3
"""Link sync across the two implementations (slice 6): the real Rust desktop enable operations through the production
Java kernel and validators (A), and operations built by the phone's Java link writer through the Rust kernel and
validators (B). Host JVM with pinned AOSP org.json; no phone, no network, no keys.

A: cargo writes the whole phone-side history (baseline, phone link operations, the REAL desktop enable operation) as wire
   JSON; SyncCrossHost replays it (SyncGraph.validateChanges, SyncProtocol receive, SyncGraph.projectLinks) and must print
   exactly the expectation Rust computed (links, skipped, conflicts, register alternatives, enable operations).
B: SyncCrossHost captures operations with SyncGraph.linkWriter/linkChanges/linkMarker; the Rust test replays them
   (validate, graph validate_changes, receive, project_links) and must compute the same expectation Java printed.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

OWNER = 'org.example.amipro'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json-classes', required=True, type=Path)
    parser.add_argument('--archive-classes', required=True, type=Path, help='java_archive_host classes (SyncGraph dependencies)')
    parser.add_argument('--output', required=True, type=Path, help='fresh disposable directory')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    out = args.output.resolve()
    out.mkdir(mode=0o700)
    classes, scenarios = out / 'classes', out / 'scenarios'
    classes.mkdir()
    scenarios.mkdir()
    app = root / 'app/src/main/java/org/stackdocs/app'
    sources = [app / n for n in ('ArchiveJson.java', 'SyncProtocol.java', 'SyncGraph.java')] + [root / 'desktop/tools/java/SyncCrossHost.java']
    classpath = f'{args.json_classes.resolve()}:{args.archive_classes.resolve()}'
    compiler = ['javac'] if shutil.which('javac') else ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main']
    subprocess.run([*compiler, '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes), *map(str, sources)], check=True)
    host = ['java', '-cp', f'{classes}:{classpath}', 'org.stackdocs.app.SyncCrossHost']
    env = {**os.environ, 'CARGO_HOME': str(Path.home() / '.cache/stack-cargo-home'),
           'STACK_LINK_CROSS_DIR': str(scenarios)}
    cargo = ['cargo', 'test', '--offline', '-p', 'stack-core', '--test', 'sync_links']

    # A: Rust writes the scenarios; Java replays them.
    subprocess.run([*cargo, 'cross_a'], cwd=root / 'desktop', env=env, check=True)
    cases = []
    a_files = sorted(scenarios.glob('A-*.json'))
    assert len(a_files) >= 4, a_files
    for path in a_files:
        scenario = json.loads(path.read_text())
        result = subprocess.run([*host, 'replay', str(path)], capture_output=True, text=True)
        if result.returncode != 0:
            cases.append({'name': scenario['name'], 'passed': False, 'java_error': result.stderr.strip()[-2000:] or result.stdout.strip()})
            continue
        actual = json.loads(result.stdout)
        expect = scenario['expect']
        diff = [k for k in expect if actual.get(k) != expect[k]]
        # The real desktop operation really is own-only and concurrent with the phone's cells, as Java sees it.
        desktop = scenario['desktop']
        mine = [e for e in actual['enable_ops'] if e['id']['replica'] == desktop]
        theirs = [e for e in actual['enable_ops'] if e['id']['replica'] != desktop]
        shape_ok = len(mine) == 1 and len(theirs) == 1 and mine[0]['context'] == (
            {desktop: mine[0]['id']['sequence'] - 1} if mine[0]['id']['sequence'] > 1 else {}) \
            and theirs[0]['id']['sequence'] > mine[0]['context'].get(theirs[0]['id']['replica'], 0)
        cases.append({'name': scenario['name'], 'passed': not diff and shape_ok, 'differs': diff, 'own_only_contexts': shape_ok,
                      'links': len(actual['links']), 'skipped': actual['skipped'], 'conflicted': actual['conflicted']})
    # The comparison can fail: a doctored expectation must differ.
    doctored = json.loads(a_files[0].read_text())
    doctored['expect']['skipped'] += 1
    bad = out / 'doctored.json'
    bad.write_text(json.dumps(doctored))
    actual = json.loads(subprocess.check_output([*host, 'replay', str(bad)], text=True))
    assert actual != doctored['expect'], 'comparison cannot fail'

    # B: Java writes the scenarios; Rust replays them.
    subprocess.run([*host, 'capture', str(scenarios)], check=True)
    capture = json.loads((scenarios / 'B-java-capture.json').read_text())
    notes = {n['row']: n for n in capture['notes']}
    outcomes = {name: note['outcome'] for name, note in notes.items()}
    expected_outcomes = {'plain': 'emitted', 'protected': 'emitted', 'empty-text': 'emitted', 'text-over-1MiB': 'cell_skipped',
                         'preview-over-32MiB': 'cell_skipped', 'title-over-limit-refused': 'row_refused',
                         'filen-path-over-1024': 'cell_skipped', 'gone-from-start': 'emitted', 'trashed': 'emitted',
                         'text-with-nul': 'cell_skipped', 'over-link-limit-refused': 'row_refused', 'no-filen': 'emitted',
                         'protected-with-preview': 'emitted', 'trashed-now-gone': 'emitted', 'plain-edited': 'emitted', 'new-row': 'emitted',
                         'foreign-live': 'emitted', 'foreign-unshared-trashed': 'emitted', 'foreign-to-be-gone': 'emitted', 'foreign-protected': 'emitted',
                         'foreign-empty-name-refused': 'row_refused', 'foreign-updated': 'emitted', 'foreign-now-gone': 'emitted'}
    assert outcomes == expected_outcomes, outcomes
    by_id = {link['link_id']: link for link in capture['expect']['links']}

    def link(n):
        return by_id.get(f'00000000-0000-0000-0000-{7000 + n:012d}')
    assert link(2)['outcome'] == 'applied_metadata_only' and link(2)['text'] is None and link(2)['preview'] is None
    assert link(13)['outcome'] == 'applied_metadata_only' and link(13)['preview'] is None
    assert link(3)['text'] is None and link(4)['text'] is None and link(4)['content'] is not None
    assert link(5)['preview'] is None and link(5)['text'] is not None
    assert link(6) is None and link(11) is None
    assert link(7)['filen'] is None and link(10)['text'] is None
    assert link(8)['outcome'] == 'gone' and link(9)['outcome'] == 'gone'
    assert link(1)['title']['title'] == 'Budget 2' and link(1)['text']['length'] == len('quarterly zebra budget revised')
    assert link(12)['filen'] is None and link(14)['outcome'] == 'applied'
    # Slice 7: the partner's records keep origin, name and generation through Java writer -> Rust kernel/projection.
    assert link(15)['content']['origin'] == 'foreign' and link(15)['content']['shared_by'] == 'Samantha' and link(15)['content']['foreign_generation'] == 4
    assert link(15)['title']['title'] == 'Shared budget v2' and link(15)['text'] is not None
    assert link(16)['state'] == 'trashed' and link(16)['content']['origin'] == 'foreign'
    assert link(17)['outcome'] == 'gone' and link(19) is None
    assert link(18)['outcome'] == 'applied_metadata_only' and link(18)['text'] is None and link(18)['content']['origin'] == 'foreign'
    assert link(1)['content'].get('origin') is None, 'an own record never carries the foreign fields'
    assert capture['expect']['skipped'] == 0 and capture['expect']['conflicted'] == 0 and capture['expect']['conflict_keys'] == []
    rust = subprocess.run([*cargo, 'cross_b', '--', '--ignored'], cwd=root / 'desktop', env=env, capture_output=True, text=True)
    sys.stderr.write(rust.stdout[-3000:] + rust.stderr[-3000:] if rust.returncode else '')
    cases.append({'name': 'java-capture-through-rust', 'passed': rust.returncode == 0})

    report = {'status': 'passed' if all(c['passed'] for c in cases) else 'failed', 'cases': cases, 'android_runtime': False}
    (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))
    if report['status'] != 'passed':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
