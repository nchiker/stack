#!/usr/bin/env python3
"""Publish the static partner start page (site/start/) to https://nchiker.github.io/stack-flatpak/start/.

    python3 desktop/tools/publish_start_page.py            # dry run: copies into a temp dir, nothing else
    python3 desktop/tools/publish_start_page.py --push     # copy into <site>/start/, commit and push
    python3 desktop/tools/publish_start_page.py --selftest

<site> defaults to ~/.cache/stack-flatpak-site (the Pages checkout also used by publish_apt_repo.py).
The page is checked first and refused if it has external URLs, extra scripts, more than 50 KB of text/CSS
(images excluded) or anything that looks like a key, token or email address.
The page has no images.
"""
import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parents[2] / 'site' / 'start'
MAX_BYTES = 50 * 1024
ALLOWED_URL = re.compile(r'^https://github\.com/nchiker/stack(/|$)')
TEXT_SUFFIXES = {'.html', '.css', '.js', '.txt', '.svg'}
SECRETS = [
    re.compile(r'[\w.+-]+@[\w-]+\.[\w.-]+'),
    re.compile(r'-----BEGIN [A-Z ]*(PRIVATE )?KEY'),
    re.compile(r'\b(ghp|gho|ghs|github_pat|xox[bp]|sk|AKIA)[-_A-Za-z0-9]{16,}'),
    re.compile(r'(?i)\b(api[_-]?key|secret|token|password)\b\s*[:=]'),
    re.compile(r'\b[A-Fa-f0-9]{32,}\b'),
    re.compile(r'\b[A-Za-z0-9+_-]{40,}={0,2}'),
]


def check(src):
    """Return a list of problems found in the page folder (empty means fine)."""
    problems = []
    src = Path(src)
    if not (src / 'index.html').is_file():
        return ['index.html missing']
    total = 0
    for f in sorted(p for p in src.rglob('*') if p.is_file()):
        if f.suffix.lower() in TEXT_SUFFIXES:
            text = f.read_text(encoding='utf-8')
            total += len(text.encode())
            for rx in SECRETS:
                if rx.search(text):
                    problems.append(f'{f.name}: looks like a key, token or email ({rx.pattern[:20]}...)')
            for url in re.findall(r'(?:https?:)?//[^\s"\'<>)]+', text):
                if not ALLOWED_URL.match(url):
                    problems.append(f'{f.name}: external URL {url}')
    if total > MAX_BYTES:
        problems.append(f'page is {total} bytes, over {MAX_BYTES}')
    html = (src / 'index.html').read_text(encoding='utf-8')
    scripts = re.findall(r'<script\b([^>]*)>', html, re.I)
    if len(scripts) > 1 or any('src' in s.lower() for s in scripts):
        problems.append('only one inline script is allowed')
    for ref in re.findall(r'(?:src|href)="([^"]+)"', html):
        if ref.startswith(('http', '//')) and not ALLOWED_URL.match(ref):
            problems.append(f'index.html: external reference {ref}')
    return problems


def copy(src, dest):
    if dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(src, dest)


def selftest():
    bad = {
        'external': '<a href="https://example.com/x">x</a>',
        'email': '<p>write to me@example.org</p>',
        'token': '<p>' + 'gh' + 'p_' + 'a' * 36 + '</p>',  # built at run time so the source holds no token-shaped text
        'script': '<script src="x.js"></script>',
        'two scripts': '<script>1</script><script>2</script>',
        'big': '<p>' + 'a ' * 30000 + '</p>',
    }
    for name, extra in bad.items():
        with tempfile.TemporaryDirectory() as t:
            copy(SRC, Path(t) / 's')
            index = Path(t) / 's' / 'index.html'
            index.write_text(index.read_text().replace('</body>', extra + '</body>'))
            assert check(Path(t) / 's'), f'{name} was not caught'
    problems = check(SRC)
    assert not problems, problems
    html = (SRC / 'index.html').read_text()
    assert len(re.findall(r'<script', html)) == 1 and '<noscript>' in html and 'href="../apt/"' in html
    assert not (SRC / 'img').exists() and '<img' not in html
    print('selftest ok')


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--site', type=Path, default=Path.home() / '.cache/stack-flatpak-site')
    p.add_argument('--push', action='store_true', help='copy into the Pages checkout, commit and push')
    p.add_argument('--selftest', action='store_true')
    a = p.parse_args()
    if a.selftest:
        return selftest()
    problems = check(SRC)
    if problems:
        sys.exit('Refusing to publish:\n  ' + '\n  '.join(problems))
    if not a.push:
        out = Path(tempfile.mkdtemp(prefix='stack-start-')) / 'start'
        copy(SRC, out)
        print(f'Dry run: copied to {out} (nothing pushed).')
        return
    if not (a.site / '.git').is_dir():
        sys.exit(f'No Pages checkout at {a.site}; run publish_apt_repo.py once first.')
    copy(SRC, a.site / 'start')
    subprocess.run(['git', '-C', str(a.site), 'add', '-A'], check=True)
    if subprocess.run(['git', '-C', str(a.site), 'diff', '--cached', '--quiet']).returncode:
        subprocess.run(['git', '-C', str(a.site), 'commit', '-q', '-m', 'Publish Stack start page'], check=True)
        subprocess.run(['git', '-C', str(a.site), '-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential',
                        'push', 'origin', 'HEAD:main'], check=True)
    print('Published start page.')


if __name__ == '__main__':
    main()
