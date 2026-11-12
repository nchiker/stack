#!/usr/bin/env python3
"""Publish a built Stack Flatpak to Stack's signed Flatpak repository on GitHub Pages.

Run after build_flatpak.py:

    python3 desktop/tools/publish_flatpak_repo.py --build .artifacts/stack-release-YYYYMMDD/flatpak --push

It copies the new commit of app/org.stackdocs.desktop/x86_64/stable from the build's
OSTree repo into the Pages checkout (default ~/.cache/stack-flatpak-site, a clone of
nchiker/stack-flatpak), signs commit and summary with the repository key from
~/APK-Keys/stack-flatpak/ (imported into a throwaway keyring that is deleted afterwards;
the secret is never printed), keeps the newest three versions with static deltas, writes
the .flatpakrepo / .flatpakref files people install from, and with --push commits and
pushes the site. Installed copies then update with `flatpak update` or the app store.
"""
import argparse
import base64
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

APP_ID = 'org.stackdocs.desktop'
BRANCH = 'stable'
REF = f'app/{APP_ID}/x86_64/{BRANCH}'
SITE_REPO = 'https://github.com/nchiker/stack-flatpak.git'
BASE_URL = 'https://nchiker.github.io/stack-flatpak/'
RUNTIME_REPO = 'https://dl.flathub.org/repo/flathub.flatpakrepo'
HOMEPAGE = 'https://github.com/nchiker/stack'
KEEP = 3
MAX_FILE = 95 * 1024 * 1024  # GitHub refuses files over 100 MB


def run(*command, **options):
    return subprocess.run([str(part) for part in command], check=True, **options)


def write(path, text):
    path.write_text(text)
    print(f'wrote {path.name}')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--build', required=True, type=Path, help='output folder of build_flatpak.py (holds repo/)')
    parser.add_argument('--site', type=Path, default=Path.home() / '.cache/stack-flatpak-site')
    parser.add_argument('--keys', type=Path, default=Path(os.environ.get('STACK_FLATPAK_KEYS', Path.home() / 'APK-Keys/stack-flatpak')))
    parser.add_argument('--push', action='store_true', help='commit and push the site to GitHub Pages')
    args = parser.parse_args()

    source = args.build.resolve() / 'repo'
    if not (source / 'config').is_file():
        parser.error(f'No OSTree repo at {source}; run build_flatpak.py first')
    fingerprint = (args.keys / 'fingerprint.txt').read_text().strip()
    public = (args.keys / 'flatpak-signing-public.asc').read_bytes()
    secret = args.keys / 'flatpak-signing-secret.asc'
    if not secret.is_file():
        parser.error(f'Signing key missing in {args.keys}')

    site = args.site
    if not (site / '.git').is_dir():
        site.parent.mkdir(parents=True, exist_ok=True)
        run('git', 'clone', SITE_REPO, site)
    repo = site / 'repo'
    if not (repo / 'config').is_file():
        run('ostree', 'init', '--mode=archive-z2', f'--repo={repo}')

    with tempfile.TemporaryDirectory(prefix='stack-flatpak-gpg-') as home:
        os.chmod(home, 0o700)
        env = dict(os.environ, GNUPGHOME=home)
        run('gpg', '--batch', '--quiet', '--import', secret, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        listed = subprocess.check_output(['gpg', '--batch', '--with-colons', '--list-secret-keys'], env=env, text=True)
        if fingerprint not in listed:
            parser.error('The signing key does not match fingerprint.txt')
        sign = [f'--gpg-sign={fingerprint}', f'--gpg-homedir={home}']
        run('flatpak', 'build-commit-from', *sign, f'--src-repo={source}', repo, REF)
        run('flatpak', 'build-update-repo', *sign, '--generate-static-deltas', '--prune', f'--prune-depth={KEEP - 1}',
            '--title=Stack', f'--default-branch={BRANCH}', f'--homepage={HOMEPAGE}', repo)
        run('gpgconf', '--kill', 'all', env=env)

    key = base64.b64encode(subprocess.check_output(['gpg', '--dearmor'], input=public)).decode()
    write(site / 'stack.flatpakrepo', f'''[Flatpak Repo]
Title=Stack
Url={BASE_URL}repo/
Homepage={HOMEPAGE}
Comment=Stack desktop, signed updates
DefaultBranch={BRANCH}
GPGKey={key}
''')
    write(site / f'{APP_ID}.flatpakref', f'''[Flatpak Ref]
Title=Stack
Name={APP_ID}
Branch={BRANCH}
Url={BASE_URL}repo/
SuggestRemoteName=stack
Homepage={HOMEPAGE}
IsRuntime=false
GPGKey={key}
RuntimeRepo={RUNTIME_REPO}
''')
    write(site / 'index.html', f'''<!doctype html><meta charset="utf-8"><title>Stack Flatpak</title>
<h1>Stack desktop</h1>
<p>Install once, then updates arrive through your app store or <code>flatpak update</code>:</p>
<pre>flatpak install --user {BASE_URL}{APP_ID}.flatpakref</pre>
<p>Repository key fingerprint: <code>{fingerprint}</code>. Source: <a href="{HOMEPAGE}">{HOMEPAGE}</a></p>
''')
    (site / '.nojekyll').touch()

    large = [p for p in repo.rglob('*') if p.is_file() and p.stat().st_size > MAX_FILE]
    if large:
        raise SystemExit(f'Files too large for GitHub Pages: {large}')
    run('ostree', f'--repo={repo}', 'log', REF, stdout=subprocess.DEVNULL)
    if args.push:
        run('git', '-C', site, 'add', '-A')
        if subprocess.run(['git', '-C', str(site), 'diff', '--cached', '--quiet']).returncode:
            run('git', '-C', site, 'commit', '-q', '-m', 'Publish Stack desktop update')
            run('git', '-C', site, '-c', 'credential.helper=', '-c', 'credential.helper=!gh auth git-credential',
                'push', 'origin', 'HEAD:main')
    print(f'Published {REF} to {site} (pushed: {args.push}). Install: flatpak install --user {BASE_URL}{APP_ID}.flatpakref')


if __name__ == '__main__':
    main()
