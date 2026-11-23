#!/usr/bin/env python3
"""Isolated test of publish_apt_repo.py and package_apt_setup_deb.py.

Throwaway signing key and temp folders only; no network, no install on this computer, nothing pushed.
Run: python3 desktop/tools/test_apt_repo.py
"""
import gzip
import os
import subprocess
import sys
import tempfile
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import package_apt_setup_deb
import package_suite_deb

failures = []


def check(name, ok, detail=''):
    print(('PASS ' if ok else 'FAIL ') + name + (f': {detail}' if detail and not ok else ''), flush=True)
    if not ok:
        failures.append(name)


def sh(*cmd, **kw):
    return subprocess.run([str(c) for c in cmd], capture_output=True, text=kw.pop('text', True), **kw)


def fake_deb(work, version, package='stack-docs'):
    tree = work / f'tree-{package}-{version}'
    (tree / 'DEBIAN').mkdir(parents=True)
    (tree / 'usr/share/stack-docs-test').mkdir(parents=True)
    (tree / 'usr/share/stack-docs-test/hello').write_text(version)
    (tree / 'DEBIAN/control').write_text(f'Package: {package}\nVersion: {version}\nSection: misc\nPriority: optional\n'
                                         'Architecture: amd64\nMaintainer: Test <t@example.invalid>\nDescription: fake\n')
    deb = work / f'{package}_{version}_amd64.deb'
    subprocess.run(['dpkg-deb', '--root-owner-group', '--build', str(tree), str(deb)], check=True, capture_output=True)
    return deb


def main():
    with tempfile.TemporaryDirectory(prefix='stack-apt-test-') as tmp:
        work = Path(tmp)
        keys, site = work / 'keys', work / 'site'
        publish = [sys.executable, '-I', str(TOOLS / 'publish_apt_repo.py'), '--keys', str(keys), '--site', str(site), '--shipped-key', str(keys / 'apt-signing-public.asc')]

        r = sh(*publish, '--make-key')
        fp = (keys / 'fingerprint.txt').read_text().strip() if (keys / 'fingerprint.txt').exists() else ''
        check('make-key creates the pair', r.returncode == 0 and len(fp) == 40, r.stderr)
        check('make-key prints only the fingerprint, not key material', 'BEGIN PGP' not in r.stdout + r.stderr and fp in r.stdout)
        check('key files are mode 0600', all(((keys / n).stat().st_mode & 0o777) == 0o600
              for n in ('fingerprint.txt', 'apt-signing-public.asc', 'apt-signing-secret.asc')))
        r = sh(*publish, '--make-key')
        check('make-key refuses to overwrite', r.returncode != 0)

        debs = [fake_deb(work, v) for v in ('1.0.0', '1.1.0', '1.2.0', '1.3.0', '1.10.0')]
        old, newest = debs[0], debs[-1]
        for d in debs:
            r = sh(*publish, '--deb', d)
            if r.returncode:
                check('publish ' + d.name, False, r.stderr)
                return
        apt = site / 'apt'
        pool = sorted(p.name for p in (apt / 'pool/main').glob('stack-docs_*.deb'))
        check('newest 3 versions kept in pool', pool == ['stack-docs_1.10.0_amd64.deb', 'stack-docs_1.2.0_amd64.deb', 'stack-docs_1.3.0_amd64.deb'], str(pool))
        suite = apt / 'dists/stable'
        for rel in ('InRelease', 'Release', 'Release.gpg', 'main/binary-amd64/Packages', 'main/binary-amd64/Packages.gz'):
            check(f'{rel} exists', (suite / rel).is_file())
        check('index.html and key published', (apt / 'index.html').is_file() and (apt / 'stack-apt.gpg').is_file())
        check('Packages.gz matches Packages', gzip.decompress((suite / 'main/binary-amd64/Packages.gz').read_bytes())
              == (suite / 'main/binary-amd64/Packages').read_bytes())

        # Signature check with gpg against the dearmored key only.
        with tempfile.TemporaryDirectory(prefix='stack-apt-verify-') as home:
            os.chmod(home, 0o700)
            env = dict(os.environ, GNUPGHOME=home)
            ring = Path(home) / 'ring.gpg'
            ring.write_bytes((apt / 'stack-apt.gpg').read_bytes())
            def verify(*files):
                return subprocess.run(['gpg', '--batch', '--no-default-keyring', '--keyring', str(ring), '--status-fd', '1',
                                       '--verify', *map(str, files)], env=env, capture_output=True, text=True)
            r = verify(suite / 'InRelease')
            check('InRelease signature valid', f'VALIDSIG {fp}' in r.stdout, r.stdout + r.stderr)
            r = verify(suite / 'Release.gpg', suite / 'Release')
            check('Release.gpg signature valid', f'VALIDSIG {fp}' in r.stdout, r.stdout + r.stderr)
            tampered = work / 'InRelease.bad'
            tampered.write_text((suite / 'InRelease').read_text().replace('Suite: stable', 'Suite: evil'))
            check('tampered InRelease rejected', 'BADSIG' in verify(tampered).stdout)
            subprocess.run(['gpgconf', '--kill', 'all'], env=env)

        # The first-install package.
        setup = package_apt_setup_deb.build(keys / 'apt-signing-public.asc', work / 'setup')
        info = sh('dpkg-deb', '-f', setup)
        check('setup deb: all architectures, no Depends', 'Architecture: all' in info.stdout and 'Depends' not in info.stdout, info.stdout)
        listing = sh('dpkg-deb', '-c', setup).stdout
        check('setup deb ships sources file and keyring', 'etc/apt/sources.list.d/stack-docs.sources' in listing
              and 'usr/share/keyrings/stack-apt.gpg' in listing, listing)
        ex = work / 'extracted'
        subprocess.run(['dpkg-deb', '-x', str(setup), str(ex)], check=True)
        src = (ex / 'etc/apt/sources.list.d/stack-docs.sources').read_text()
        check('sources file is correct deb822', all(s in src for s in (
            'URIs: https://nchiker.github.io/stack-flatpak/apt/', 'Suites: stable', 'Components: main',
            'Signed-By: /usr/share/keyrings/stack-apt.gpg')), src)
        check('packaged keyring equals published key', (ex / 'usr/share/keyrings/stack-apt.gpg').read_bytes() == (apt / 'stack-apt.gpg').read_bytes())

        # apt-get in a throwaway root: package found, newer version offered as an upgrade.
        a = work / 'aptroot'
        for d in ('state/lists/partial', 'cache/archives/partial', 'parts', 'trusted.d', 'prefs.d', 'conf.d', 'lib-dpkg'):
            (a / d).mkdir(parents=True)
        (a / 'sources.list').write_text(f'deb [signed-by={apt / "stack-apt.gpg"}] file://{apt} stable main\n')
        (a / 'lib-dpkg/status').write_text('Package: stack-docs\nStatus: install ok installed\nVersion: 1.0.0\n'
                                           'Architecture: amd64\nMaintainer: Test <t@example.invalid>\nSection: misc\nDescription: fake\n')
        (a / 'empty.conf').write_text('')
        opts = [f'-oDir::Etc::sourcelist={a / "sources.list"}', f'-oDir::Etc::sourceparts={a / "parts"}',
                f'-oDir::State={a / "state"}', f'-oDir::State::status={a / "lib-dpkg/status"}', f'-oDir::Cache={a / "cache"}',
                f'-oDir::Etc::trusted={a / "no-trusted.gpg"}', f'-oDir::Etc::trustedparts={a / "trusted.d"}',
                f'-oDir::Etc::preferences={a / "no-prefs"}', f'-oDir::Etc::preferencesparts={a / "prefs.d"}',
                f'-oDir::Etc::parts={a / "conf.d"}', '-oDebug::NoLocking=1', '-oAPT::Sandbox::User=', '-oAPT::Architecture=amd64',
                '-oAPT::Architectures::=amd64', '-oDpkg::Options::=--admindir=' + str(a / 'lib-dpkg')]
        env = dict(os.environ, APT_CONFIG=str(a / 'empty.conf'))
        r = sh('apt-get', *opts, 'update', env=env)
        check('apt-get update accepts the signed repository', r.returncode == 0 and 'NO_PUBKEY' not in r.stderr and 'not signed' not in r.stderr.lower(), r.stdout + r.stderr)
        r = sh('apt-cache', *opts, 'policy', 'stack-docs', env=env)
        check('package found with candidate 1.10.0', 'Candidate: 1.10.0' in r.stdout, r.stdout + r.stderr)
        r = sh('apt-get', '-s', *opts, 'upgrade', env=env)
        check('apt-get -s offers the upgrade', 'Inst stack-docs [1.0.0] (1.10.0' in r.stdout, r.stdout + r.stderr)

        # A repository signed by a different key must be rejected.
        other = work / 'otherkeys'
        sh(sys.executable, '-I', TOOLS / 'publish_apt_repo.py', '--keys', other, '--make-key')
        (a / 'sources.list').write_text(f'deb [signed-by={ex / "usr/share/keyrings/stack-apt.gpg"}] file://{apt} stable main\n')
        r = sh('apt-get', *opts, 'update', env=env)
        check('setup-deb keyring (same key) works', r.returncode == 0, r.stdout + r.stderr)
        sh('gpg', '--batch', '--dearmor', '-o', other / 'wrong.gpg', other / 'apt-signing-public.asc')
        (a / 'sources.list').write_text(f'deb [signed-by={other / "wrong.gpg"}] file://{apt} stable main\n')
        r = sh('apt-get', *opts, 'update', env=env)
        check('repository rejected with the wrong key', r.returncode != 0 or 'NO_PUBKEY' in r.stderr or 'not signed' in r.stderr.lower(), r.stdout + r.stderr)

        # Suite metapackage with two different packages in one pool.
        suite_deb = package_suite_deb.build(work / 'suite')
        again = package_suite_deb.build(work / 'suite2')
        check('suite deb is reproducible', suite_deb.read_bytes() == again.read_bytes())
        sinfo = sh('dpkg-deb', '-f', suite_deb)
        check('suite deb: metapackage, all, depends on both', 'Section: metapackages' in sinfo.stdout and 'Architecture: all' in sinfo.stdout
              and 'Depends: stack-docs, amipro-linux' in sinfo.stdout, sinfo.stdout)
        check('suite deb ships only the doc note', [l.split()[-1] for l in sh('dpkg-deb', '-c', suite_deb).stdout.splitlines() if not l.startswith('d')]
              == ['./usr/share/doc/stack-suite/README.Debian'], sh('dpkg-deb', '-c', suite_deb).stdout)
        for d in (fake_deb(work, '2.0.0', 'amipro-linux'), suite_deb):
            r = sh(*publish, '--deb', d)
            check('publish ' + d.name, r.returncode == 0, r.stderr)
        pkgs = (suite / 'main/binary-amd64/Packages').read_text()
        check('Packages lists all three packages', all(f'Package: {n}\n' in pkgs for n in ('stack-docs', 'amipro-linux', 'stack-suite')), pkgs)
        check('stack-docs still limited to 3 versions', len(list((apt / 'pool/main').glob('stack-docs_*.deb'))) == 3)
        (a / 'sources.list').write_text(f'deb [signed-by={apt / "stack-apt.gpg"}] file://{apt} stable main\n')
        (a / 'lib-dpkg/status').write_text('')
        r = sh('apt-get', *opts, 'update', env=env)
        check('apt-get update with suite repository', r.returncode == 0, r.stdout + r.stderr)
        r = sh('apt-get', '-s', *opts, 'install', 'stack-suite', env=env)
        check('apt-get -s install stack-suite pulls both dependencies from the repo',
              r.returncode == 0 and 'Inst stack-docs (1.10.0' in r.stdout and 'Inst amipro-linux (2.0.0' in r.stdout
              and 'Inst stack-suite (1.0' in r.stdout, r.stdout + r.stderr)

        big = work / 'stack-docs_9.9.9_amd64.deb'
        with big.open('wb') as f:
            f.truncate(96 * 1024 * 1024)
        r = sh(*publish, '--deb', big)
        check('over-95MB file refused', r.returncode != 0 and 'too large' in (r.stderr + r.stdout), r.stderr)

    print('\n' + ('All passed.' if not failures else f'{len(failures)} failed.'))
    return 1 if failures else 0


if __name__ == '__main__':
    sys.exit(main())
