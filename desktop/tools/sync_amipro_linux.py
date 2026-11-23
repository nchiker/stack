#!/usr/bin/env python3
"""Fetch and verify the latest Ami Pro Linux release (nchiker/AmiPro-Linux) for the Stack apt repository.

    python3 desktop/tools/sync_amipro_linux.py --download-dir D [--dry-run]
    python3 desktop/tools/sync_amipro_linux.py --selftest

Uses `gh api` / `gh release download` against that one repository (HTTPS). The release must have tag vX.Y.Z, asset
amipro-linux_X.Y.Z_amd64.deb and SHA256SUMS. The .deb is checked against SHA256SUMS (a missing or mismatching sum
refuses), `dpkg-deb --field` must say Package amipro-linux, Architecture amd64 and Version X.Y.Z (the tag). Nothing
from the package is ever executed or installed. On success the only line on stdout is the verified .deb path, so
    publish_apt_repo.py --deb "$(python3 desktop/tools/sync_amipro_linux.py --download-dir D)"
works. --dry-run only reads the release metadata and prints what would be downloaded. Publishes nothing.
"""
import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = 'nchiker/AmiPro-Linux'
PACKAGE = 'amipro-linux'
ARCH = 'amd64'
TAG_RE = re.compile(r'v(\d+\.\d+\.\d+)')
URL_PREFIX = f'https://github.com/{REPO}/'


def say(*parts):
    print(*parts, file=sys.stderr)


def latest_release():
    out = subprocess.check_output(['gh', 'api', f'repos/{REPO}/releases/latest'], text=True)
    return json.loads(out)


def check_release(release):
    """Return (tag, version, deb_name) or raise SystemExit when the release is not shaped as RELEASING.md says."""
    tag = release.get('tag_name', '')
    m = TAG_RE.fullmatch(tag)
    if not m or release.get('draft') or release.get('prerelease'):
        raise SystemExit(f'Unexpected latest release {tag!r} of {REPO}; refusing.')
    version = m.group(1)
    deb = f'{PACKAGE}_{version}_{ARCH}.deb'
    assets = {a.get('name'): a for a in release.get('assets', [])}
    for name in (deb, 'SHA256SUMS'):
        a = assets.get(name)
        if a is None:
            raise SystemExit(f'Release {tag} has no asset {name}; refusing.')
        url = a.get('browser_download_url', '')
        if not url.startswith(URL_PREFIX):
            raise SystemExit(f'Asset {name} is not served from {URL_PREFIX}; refusing.')
    return tag, version, deb


def field(deb, name):
    return subprocess.check_output(['dpkg-deb', '--field', str(deb), name], text=True).strip()


def sums(path):
    """{file name: sha256} from a sha256sum-format file."""
    table = {}
    for line in Path(path).read_text().splitlines():
        m = re.fullmatch(r'([0-9a-fA-F]{64}) [ *]?([^/\s]+)', line.strip())
        if m:
            table[m.group(2)] = m.group(1).lower()
    return table


def verify(directory, tag):
    """Check the downloaded files in directory for release tag; return the .deb path or raise SystemExit."""
    m = TAG_RE.fullmatch(tag)
    if not m:
        raise SystemExit(f'Bad tag {tag!r}')
    version = m.group(1)
    name = f'{PACKAGE}_{version}_{ARCH}.deb'
    directory = Path(directory)
    deb, sumfile = directory / name, directory / 'SHA256SUMS'
    if not deb.is_file():
        raise SystemExit(f'Missing {name}; refusing.')
    if not sumfile.is_file():
        raise SystemExit('Missing SHA256SUMS; refusing.')
    expected = sums(sumfile).get(name)
    if not expected:
        raise SystemExit(f'SHA256SUMS does not list {name}; refusing.')
    digest = hashlib.sha256()
    with deb.open('rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise SystemExit(f'SHA-256 of {name} does not match SHA256SUMS; refusing.')
    for key, want in (('Package', PACKAGE), ('Architecture', ARCH), ('Version', version)):
        got = field(deb, key)
        if got != want:
            raise SystemExit(f'{name}: {key} is {got!r}, expected {want!r}; refusing.')
    return deb


def sync(download_dir, dry_run=False):
    tag, version, name = check_release(latest_release())
    if dry_run:
        say(f'Dry run: would download {name} and SHA256SUMS of {REPO} {tag} into {download_dir}/{tag}')
        return None
    target = Path(download_dir) / tag
    target.mkdir(parents=True, exist_ok=True)
    for stale in (target / name, target / 'SHA256SUMS'):
        stale.unlink(missing_ok=True)
    subprocess.run(['gh', 'release', 'download', tag, '--repo', REPO, '--dir', str(target),
                    '--pattern', name, '--pattern', 'SHA256SUMS'], check=True, stdout=subprocess.DEVNULL)
    return verify(target, tag)


def fake_deb(path, package, version, arch):
    tree = path.parent / f'tree-{package}-{version}-{arch}'
    (tree / 'DEBIAN').mkdir(parents=True)
    (tree / 'DEBIAN/control').write_text(f'Package: {package}\nVersion: {version}\nSection: misc\nPriority: optional\n'
                                         f'Architecture: {arch}\nMaintainer: Test <t@example.invalid>\nDescription: fake\n')
    subprocess.run(['dpkg-deb', '--root-owner-group', '--build', str(tree), str(path)], check=True, capture_output=True)


def selftest():
    failures = []

    def expect(name, ok):
        print(('PASS ' if ok else 'FAIL ') + name, file=sys.stderr)
        if not ok:
            failures.append(name)

    def refuses(directory, tag='v1.2.3'):
        try:
            verify(directory, tag)
        except SystemExit:
            return True
        return False

    def release(work, name, package='amipro-linux', version='1.2.3', arch='amd64', file_version='1.2.3', sums_mode='good'):
        d = work / name
        d.mkdir()
        deb = d / f'{PACKAGE}_{file_version}_{ARCH}.deb'
        fake_deb(deb, package, version, arch)
        digest = hashlib.sha256(deb.read_bytes()).hexdigest()
        if sums_mode == 'good':
            (d / 'SHA256SUMS').write_text(f'{digest}  {deb.name}\n')
        elif sums_mode == 'bad':
            (d / 'SHA256SUMS').write_text(f'{"0" * 64}  {deb.name}\n')
        elif sums_mode == 'other':
            (d / 'SHA256SUMS').write_text(f'{digest}  something-else.deb\n')
        return d

    with tempfile.TemporaryDirectory(prefix='amipro-sync-test-') as tmp:
        work = Path(tmp)
        good = release(work, 'good')
        expect('good release verifies', verify(good, 'v1.2.3') == good / 'amipro-linux_1.2.3_amd64.deb')
        expect('bad checksum refused', refuses(release(work, 'bad', sums_mode='bad')))
        expect('missing SHA256SUMS refused', refuses(release(work, 'nosums', sums_mode='none')))
        expect('sums without the package refused', refuses(release(work, 'other', sums_mode='other')))
        expect('wrong package name refused', refuses(release(work, 'pkg', package='evil')))
        expect('wrong architecture refused', refuses(release(work, 'arch', arch='arm64')))
        expect('deb version differing from tag refused', refuses(release(work, 'ver', version='1.2.4')))
        expect('file name differing from tag refused', refuses(good, 'v1.2.4'))
        expect('non-version tag refused', refuses(good, 'latest'))
        shaped = {'tag_name': 'v1.2.3', 'assets': [
            {'name': 'amipro-linux_1.2.3_amd64.deb', 'browser_download_url': URL_PREFIX + 'releases/download/v1.2.3/x'},
            {'name': 'SHA256SUMS', 'browser_download_url': URL_PREFIX + 'releases/download/v1.2.3/y'}]}
        expect('well-shaped release accepted', check_release(shaped)[0] == 'v1.2.3')
        for label, bad in (('missing sums', dict(shaped, assets=shaped['assets'][:1])),
                           ('prerelease', dict(shaped, prerelease=True)),
                           ('foreign URL', dict(shaped, assets=[dict(a, browser_download_url='http://evil.example/x') for a in shaped['assets']]))):
            try:
                check_release(bad)
                expect(f'{label} refused', False)
            except SystemExit:
                expect(f'{label} refused', True)
    print('All passed.' if not failures else f'{len(failures)} failed.', file=sys.stderr)
    return 1 if failures else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--download-dir', type=Path, help='folder to download into (a vX.Y.Z subfolder is made)')
    parser.add_argument('--dry-run', action='store_true', help='read release metadata only; download nothing')
    parser.add_argument('--selftest', action='store_true', help='offline test with fake release folders')
    args = parser.parse_args()
    if args.selftest:
        return selftest()
    if not args.download_dir:
        parser.error('give --download-dir')
    deb = sync(args.download_dir, args.dry_run)
    if deb:
        print(deb)
    return 0


if __name__ == '__main__':
    sys.exit(main())
