#!/usr/bin/env python3
"""Publish built Stack .deb packages to Stack's signed apt repository on GitHub Pages.

    python3 desktop/tools/publish_apt_repo.py --deb desktop/target/deb/stack-docs_0.6.0_amd64.deb --push
    python3 desktop/tools/publish_apt_repo.py --make-key        # once: create the stack-apt signing key pair

It writes <site>/apt/ (pool/, dists/stable/main/binary-amd64/Packages(.gz), Release, signed InRelease and Release.gpg,
the dearmored public key stack-apt.gpg and an index.html) in the Pages checkout (default ~/.cache/stack-flatpak-site,
a clone of nchiker/stack-flatpak). The signing key comes from ~/APK-Keys/stack-apt/ (fingerprint.txt,
apt-signing-public.asc, apt-signing-secret.asc), is imported into a throwaway keyring that is deleted afterwards and
is never printed. The newest 3 versions of each package are kept; files over 95 MB are refused (GitHub limit).
--also-from-amipro first fetches and verifies the latest Ami Pro Linux release (sync_amipro_linux.py) and publishes
that .deb too; any number of different packages (stack-docs, amipro-linux, stack-suite) share the one pool.
With --push the site is committed and pushed. Installed copies then update through the system updater once
the stack-docs-apt-source package (package_apt_setup_deb.py) is installed.
"""
import argparse
import gzip
import os
import re
import shutil
import subprocess
import sys
import tempfile
from contextlib import contextmanager
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
SITE_REPO = 'https://github.com/nchiker/stack-flatpak.git'
BASE_URL = 'https://nchiker.github.io/stack-flatpak/'
HOMEPAGE = 'https://github.com/nchiker/stack'
SUITE, COMPONENT, ARCH = 'stable', 'main', 'amd64'
KEEP = 3
MAX_FILE = 95 * 1024 * 1024  # GitHub refuses files over 100 MB
KEY_FILES = ('fingerprint.txt', 'apt-signing-public.asc', 'apt-signing-secret.asc')


def run(*command, **options):
    return subprocess.run([str(part) for part in command], check=True, **options)


def field(deb, name):
    return subprocess.check_output(['dpkg-deb', '--field', str(deb), name], text=True).strip()


def version_key_sort(versions):
    """Sort Debian versions oldest to newest using dpkg itself."""
    import functools

    def cmp(a, b):
        if subprocess.run(['dpkg', '--compare-versions', a, 'lt', b]).returncode == 0:
            return -1
        return 0 if a == b else 1
    return sorted(versions, key=functools.cmp_to_key(cmp))


@contextmanager
def private_gpg():
    with tempfile.TemporaryDirectory(prefix='stack-apt-gpg-') as home:
        os.chmod(home, 0o700)
        env = dict(os.environ, GNUPGHOME=home)
        try:
            yield env
        finally:
            subprocess.run(['gpgconf', '--kill', 'all'], env=env, check=False)


def fingerprints(public):
    """Fingerprints in an armored or binary PUBLIC key (bytes). Never given a secret key file."""
    with private_gpg() as env:
        out = subprocess.run(['gpg', '--batch', '--show-keys', '--with-colons', '--fingerprint', '-'], input=public, capture_output=True, env=env).stdout.decode()
    return sorted(line.split(':')[9] for line in out.splitlines() if line.startswith('fpr:'))


def check_public_key(public, shipped_file=None):
    """The key the packages ship (packaging/stack-apt-public.asc) must be the key that signs the repository."""
    shipped = (shipped_file or Path(__file__).resolve().parents[1] / 'packaging/stack-apt-public.asc').read_bytes()
    mine, theirs = fingerprints(public), fingerprints(shipped)
    if not mine or mine != theirs:
        raise SystemExit('The public key in the signing folder does not match desktop/packaging/stack-apt-public.asc. '
                         'Installed packages would not trust this repository. Nothing was published.')


def make_key(keys):
    if any((keys / n).exists() for n in KEY_FILES):
        raise SystemExit(f'A key already exists in {keys}; not touching it.')
    keys.mkdir(parents=True, exist_ok=True, mode=0o700)
    with private_gpg() as env:
        run('gpg', '--batch', '--quiet', '--pinentry-mode', 'loopback', '--passphrase', '',
            '--quick-gen-key', 'Stack apt repository <stack-apt@users.noreply.github.com>', 'ed25519', 'sign', 'never',
            env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        listed = subprocess.check_output(['gpg', '--batch', '--with-colons', '--list-secret-keys'], env=env, text=True)
        fingerprint = re.search(r'^fpr:+([0-9A-F]{40}):', listed, re.M).group(1)
        public = subprocess.check_output(['gpg', '--batch', '--armor', '--export', fingerprint], env=env)
        secret = subprocess.check_output(['gpg', '--batch', '--pinentry-mode', 'loopback', '--passphrase', '',
                                          '--armor', '--export-secret-keys', fingerprint], env=env)
    for name, data in (('fingerprint.txt', (fingerprint + '\n').encode()), ('apt-signing-public.asc', public),
                       ('apt-signing-secret.asc', secret)):
        fd = os.open(keys / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'wb') as f:
            f.write(data)
    print(f'Created the apt signing key in {keys}. Fingerprint: {fingerprint}')
    print('Back the key folder up (for example through Key Vault); it is the only copy.')


def prune(pool):
    by_name = {}
    for deb in pool.glob('*.deb'):
        by_name.setdefault(field(deb, 'Package'), {})[field(deb, 'Version')] = deb
    for versions in by_name.values():
        for old in version_key_sort(versions)[:-KEEP]:
            versions[old].unlink()
            print(f'removed old {versions[old].name}')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--deb', action='append', type=Path, default=[], help='built .deb to publish (repeatable)')
    parser.add_argument('--also-from-amipro', action='store_true', help='also fetch, verify and publish the latest Ami Pro Linux release')
    parser.add_argument('--site', type=Path, default=Path.home() / '.cache/stack-flatpak-site')
    parser.add_argument('--keys', type=Path, default=Path.home() / 'APK-Keys/stack-apt')
    parser.add_argument('--make-key', action='store_true', help='create the signing key pair in --keys (only if absent) and exit')
    parser.add_argument('--push', action='store_true', help='commit and push the site to GitHub Pages')
    parser.add_argument('--shipped-key', type=Path, default=None, help='public key the packages ship (default: desktop/packaging/stack-apt-public.asc); tests pass their own')
    args = parser.parse_args()

    if args.make_key:
        return make_key(args.keys)
    if args.also_from_amipro:
        import sync_amipro_linux
        with tempfile.TemporaryDirectory(prefix='stack-amipro-') as download:
            fetched = sync_amipro_linux.sync(download)
            kept = Path(tempfile.mkdtemp(prefix='stack-amipro-deb-'))
            args.deb.append(Path(shutil.copy2(fetched, kept / fetched.name)))
    if not args.deb:
        parser.error('give at least one --deb')
    for deb in args.deb:
        if not deb.is_file():
            parser.error(f'No such file: {deb}')
        if deb.stat().st_size > MAX_FILE:
            raise SystemExit(f'{deb.name} is too large for GitHub Pages ({deb.stat().st_size} bytes)')
    fingerprint = (args.keys / 'fingerprint.txt').read_text().strip()
    public = (args.keys / 'apt-signing-public.asc').read_bytes()
    check_public_key(public, args.shipped_key)
    secret = args.keys / 'apt-signing-secret.asc'
    if not secret.is_file():
        parser.error(f'Signing key missing in {args.keys}')

    site = args.site
    if not site.exists():
        site.parent.mkdir(parents=True, exist_ok=True)
        run('git', 'clone', SITE_REPO, site)
    if args.push and not (site / '.git').is_dir():
        parser.error(f'{site} is not a git checkout; cannot --push')
    apt = site / 'apt'
    pool = apt / 'pool' / COMPONENT
    pool.mkdir(parents=True, exist_ok=True)
    for deb in args.deb:
        shutil.copy2(deb, pool / deb.name)
    prune(pool)

    suite = apt / 'dists' / SUITE
    index = suite / COMPONENT / f'binary-{ARCH}'
    shutil.rmtree(suite, ignore_errors=True)
    index.mkdir(parents=True)
    packages = subprocess.check_output(['apt-ftparchive', 'packages', f'pool/{COMPONENT}'], cwd=apt)
    (index / 'Packages').write_bytes(packages)
    with gzip.GzipFile(index / 'Packages.gz', 'wb', mtime=0) as f:
        f.write(packages)
    release = subprocess.check_output(
        ['apt-ftparchive', '-o', f'APT::FTPArchive::Release::Origin=Stack', '-o', f'APT::FTPArchive::Release::Label=Stack',
         '-o', f'APT::FTPArchive::Release::Suite={SUITE}', '-o', f'APT::FTPArchive::Release::Codename={SUITE}',
         '-o', f'APT::FTPArchive::Release::Architectures={ARCH}', '-o', f'APT::FTPArchive::Release::Components={COMPONENT}',
         'release', '.'], cwd=suite)
    (suite / 'Release').write_bytes(release)

    with private_gpg() as env:
        run('gpg', '--batch', '--quiet', '--import', secret, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        listed = subprocess.check_output(['gpg', '--batch', '--with-colons', '--list-secret-keys'], env=env, text=True)
        if fingerprint not in listed:
            raise SystemExit('The signing key does not match fingerprint.txt')
        sign = ['gpg', '--batch', '--yes', '--pinentry-mode', 'loopback', '--passphrase', '', '--default-key', fingerprint]
        run(*sign, '--clearsign', '-o', suite / 'InRelease', suite / 'Release', env=env)
        run(*sign, '--armor', '--detach-sign', '-o', suite / 'Release.gpg', suite / 'Release', env=env)
    (apt / 'stack-apt.gpg').write_bytes(subprocess.check_output(['gpg', '--dearmor'], input=public))

    names = sorted(p.name for p in pool.glob('*.deb'))
    items = ''.join(f'<li>{n}</li>' for n in names)
    (apt / 'index.html').write_text(f'''<!doctype html><meta charset="utf-8"><title>Stack apt repository</title>
<h1>Stack desktop (apt)</h1>
<p>Install the stack-docs-apt-source package once (double-click it); updates then arrive through the system updater.</p>
<p>Repository: <code>{BASE_URL}apt/</code>, suite <code>{SUITE}</code>, component <code>{COMPONENT}</code>.
Key fingerprint: <code>{fingerprint}</code>. Source: <a href="{HOMEPAGE}">{HOMEPAGE}</a></p>
<ul>{items}</ul>
''')
    (site / '.nojekyll').touch()

    large = [p for p in apt.rglob('*') if p.is_file() and p.stat().st_size > MAX_FILE]
    if large:
        raise SystemExit(f'Files too large for GitHub Pages: {large}')
    if args.push:
        run('git', '-C', site, 'add', '-A')
        if subprocess.run(['git', '-C', str(site), 'diff', '--cached', '--quiet']).returncode:
            run('git', '-C', site, 'commit', '-q', '-m', 'Publish Stack apt repository update')
            run('git', '-C', site, '-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential',
                'push', 'origin', 'HEAD:main')
    print(f'Published {", ".join(names)} to {apt} (pushed: {args.push}).')


if __name__ == '__main__':
    main()
