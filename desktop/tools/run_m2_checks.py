#!/usr/bin/env python3
"""Run M2 engineering acceptance without connecting to a display or opening windows."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time


TARGETS = {
    'empty_initial_browse_ms': 500,
    'cancel_dispatch_us': 1000,
    'cancel_cleanup_ms': 1000,
    'stage_review_ms': 30000,
    'commit_browse_ms': 30000,
    'first_results_page_layout_draw_ms': 150,
    'results_page_scroll_draw_ms': 50,
    'debounced_search_ms': 5000,
    'executor_max_tick_gap_us': 150000,
    'process_peak_rss_kib': 524288,
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.absolute()
    if not out.is_relative_to('/tmp'):
        parser.error('Use a fresh disposable /tmp output root')
    os.umask(0o077)
    out.mkdir(mode=0o700)
    desktop = Path(__file__).resolve().parents[1]
    engine = desktop / 'runtime/pdfium/lib/libpdfium.so'
    env = dict(os.environ, STACK_PDFIUM_LIBRARY=str(engine), STACK_M2_PRESERVE_WORKFLOW='1',
               STACK_TESSERACT_LIBRARY=str(desktop / 'runtime/ocr/lib/libtesseract.so.5'),
               STACK_TESSDATA_DIR=str(desktop / 'runtime/ocr/tessdata'), OMP_THREAD_LIMIT='1')
    for key in ('DISPLAY', 'WAYLAND_DISPLAY', 'WAYLAND_DEBUG', 'STACK_SYNTHETIC_FOCUS_HELPER',
                'STACK_M2_PORTAL_PROTOCOL', 'AT_SPI_BUS_ADDRESS'):
        env.pop(key, None)
    for key in ('DATA', 'CONFIG', 'CACHE', 'STATE'):
        folder = out / key.lower()
        folder.mkdir(mode=0o700)
        env[f'XDG_{key}_HOME'] = str(folder)
    sources = [desktop / 'Cargo.toml', desktop / 'Cargo.lock']
    sources += [p for folder in ('crates', 'tools', 'platform') for p in (desktop / folder).rglob('*')
                if p.is_file() and p.suffix in ('.rs', '.toml', '.py')]
    report = {
        'status': 'running', 'headless': True, 'display_environment_removed': True,
        'started_utc': datetime.now(timezone.utc).isoformat(),
        'source_base': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=desktop, text=True).strip(),
        'rustc': subprocess.check_output(['rustc', '--version'], env=env, text=True).strip(),
        'pdfium_sha256': hashlib.sha256(engine.read_bytes()).hexdigest(),
        'ocr_receipt': json.loads((desktop / 'runtime/ocr/ENGINE.json').read_text()),
        'source_sha256': {str(p.relative_to(desktop)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(sources)},
        'checks': {},
    }
    def save():
        (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    commands = [
        ('fmt', ['cargo', 'fmt', '--all', '--check']),
        ('check', ['cargo', 'check', '--workspace', '--locked', '--offline']),
        ('clippy', ['cargo', 'clippy', '--workspace', '--all-targets', '--locked', '--offline', '--', '-D', 'warnings']),
        ('build', ['cargo', 'build', '--workspace', '--locked', '--offline']),
        ('test', ['cargo', 'test', '--workspace', '--locked', '--offline', '--', '--include-ignored', '--nocapture']),
        ('counts', ['cargo', 'test', '-p', 'stack-desktop', '--locked', '--offline', 'application_maximum_counts_import_search_and_worker_responsiveness', '--', '--include-ignored', '--nocapture']),
        ('software', ['cargo', 'check', '-p', 'stack-desktop', '--no-default-features', '--locked', '--offline']),
        ('portal_protocol', [sys.executable, str(desktop / 'tools/test_m2_portal_protocol.py'), '--output', str(out / 'portal')]),
    ]
    try:
        for name, command in commands:
            start = time.monotonic()
            with (out / f'{name}.log').open('w') as log:
                result = subprocess.run(command, cwd=desktop, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=240)
            report['checks'][name] = {'command': command, 'exit_code': result.returncode, 'seconds': round(time.monotonic() - start, 3)}
            save()
            print(f'{name}: {result.returncode}', flush=True)
            if result.returncode:
                raise RuntimeError(f'{name} failed; see {out}/{name}.log')
        for source in (desktop / 'tools').glob('*.py'):
            compile(source.read_bytes(), str(source), 'exec')
        log = (out / 'test.log').read_text()
        totals = re.findall(r'test result: ok\. (\d+) passed; (\d+) failed; (\d+) ignored', log)
        report['tests'] = dict(zip(('passed', 'failed', 'ignored'), [sum(int(row[n]) for row in totals) for n in range(3)]))
        assert report['tests']['passed'] and not report['tests']['failed'] and not report['tests']['ignored']
        report['workspace_maximum_counts'] = json.loads(re.search(r'M2_APPLICATION_COUNTS=(\{.*\})', log)[1])
        counts = json.loads(re.search(r'M2_APPLICATION_COUNTS=(\{.*\})', (out / 'counts.log').read_text())[1])
        report['maximum_counts'] = counts
        report['performance_targets'] = {key: {'actual': counts[key], 'maximum': value, 'met': counts[key] <= value}
                                         for key, value in TARGETS.items()}
        report['performance_scope'] = 'Fresh maximum-count test process including authored fixture/graph allocation; host debug headless widgets/executor targets, not native compositor or release frame budgets. Combined workspace-process high-water mark is recorded separately.'
        report['workflow_artifacts'] = re.findall(r'M2_HEADLESS_WORKFLOW_ARTIFACTS=(\S+)', log)
        report['status'] = 'passed' if all(p['met'] for p in report['performance_targets'].values()) else 'performance_target_unmet'
        save()
        if report['status'] != 'passed':
            raise RuntimeError(f'Performance target unmet; see {out}/report.json')
    except Exception as error:
        report.update(status='failed', error=str(error))
        save()
        raise
    print(str(out / 'report.json'), flush=True)


if __name__ == '__main__':
    main()
