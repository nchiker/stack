#!/usr/bin/env python3
"""Real-LAN link sync run: the TEST phone against a headless desktop peer, over a real TCP/TLS session.

  python3 desktop/tools/test_android_link_lan.py --scenario first_sync          (50 links, 5 x 20 MiB previews)
  python3 desktop/tools/test_android_link_lan.py --scenario all --build
  python3 desktop/tools/test_android_link_lan.py --dry-run --scenario kill --links 200   (no adb, nothing touched)
  python3 desktop/tools/test_android_link_lan.py --selftest                              (host unit lane, no phone)

Scenarios (docs/STACK_LINK_SLICE6_HANDOFF.md, "Real LAN run"):
  first_sync   N synthetic links (P with a 20 MiB preview) seeded on the phone, one Sync now, then a no-change Sync. Counts, titles,
               text and preview hashes must match on the computer; timings are recorded.
  tie_review   T links exist on both sides with the same `modified` and different hashes: the phone shows T tie reviews, each is
               decided per link (alternating phone / computer), one more Sync, both sides must hold the chosen version.
  delete_edit  first_sync, then on the phone: rename E, trash R, delete D links, Sync; the computer must follow.
  kill         first_sync with process kills: between seed and sync, and inside a running sync (force-stop at several delays);
               after every kill the phone's data must be intact; a final Sync must complete and match.
  all          the four above, each with a fresh pairing.

Safety: only the TEST phone 0A121JEC203530 and its checks package; any other serial (above all the production phone) is refused.
adb is limited to `am instrument`, `am force-stop` of the checks package, `run-as` on the sync-lan-* replica folders, and read-only
getprop/pm list. It never installs, uninstalls, clears or opens an activity. Synthetic data only; the pairing key material stays in
files of the disposable replica and is never printed. Output is counts and timings only.
Dev-only tool, standard library only. Builds nothing unless --build is given.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import shlex
import shutil
import socket
import subprocess
import sys
import time
import uuid
from pathlib import Path

TOOLS = Path(__file__).resolve().parent
REPO = TOOLS.parents[1]
sys.path.insert(0, str(TOOLS))
import test_android_archive_interop as interop  # noqa: E402  (its serial guards and preflight, unchanged)

SERIAL = interop.ALLOWED_SERIAL
DENIED = interop.DENIED_SERIAL
PACKAGE = interop.PACKAGE
INSTRUMENT = interop.INSTRUMENT
SCENARIOS = ('first_sync', 'tie_review', 'delete_edit', 'kill')
HEX64 = re.compile(r'[0-9a-fA-F]{64}')
REPLICA = re.compile(r'sync-lan-[a-z0-9-]+')
PHONE_FILE = re.compile(r'files/checks/sync-lan-[a-z0-9-]+/files/(lan-peer\.der|lan-baseline\.stackexchange|lan-identity\.der|link-lan-phase)')
PHONE_DIR = re.compile(r'files/checks/sync-lan-[a-z0-9-]+')
PREVIEW_BYTES = 20 * 1024 * 1024
FIRST_SYNC_LIMIT_S = 180   # the desktop's read timeout; the run fails past it
KILL_DELAYS = (0.3, 3.0, 8.0, 15.0, 25.0)
ADB_DEFAULT = Path.home() / '.local/share/stack-android-sdk/platform-tools/adb'
BIN = REPO / 'desktop/target/debug'


class Refused(Exception):
    pass


def scrub(text):
    return HEX64.sub('[hex64]', str(text))


def link_id(n):
    """Same as Uuid::from_u128 / LinkLanChecks.id."""
    return str(uuid.UUID(int=n))


def phone_link_id(i, tie):
    return link_id(7000 + i) if i <= tie else link_id(900000 + i)


# ---------------------------------------------------------------- arguments

def check_args(args, env=None):
    """Raise Refused (or ValueError) for anything outside the allowed envelope. Touches nothing."""
    env = os.environ if env is None else env
    if args.serial != SERIAL:
        raise Refused('only the test phone is allowed' + (' (the production phone is never used)' if args.serial == DENIED else ''))
    if env.get('ANDROID_SERIAL', SERIAL) != SERIAL:
        raise Refused('ANDROID_SERIAL points at a different device')
    if any(DENIED in str(v) for v in list(vars(args).values()) + list(env.values())):
        raise Refused('the production phone serial appears in the arguments')
    if args.package != PACKAGE:
        raise Refused('only the checks package ' + PACKAGE + ' is allowed')
    if args.scenario not in SCENARIOS + ('all',):
        raise ValueError('unknown scenario')
    if not 1 <= args.links <= 1000:
        raise ValueError('--links must be 1..1000')
    if not 0 <= args.previews <= min(args.links, 40):
        raise ValueError('--previews must be 0..min(links, 40) (each is 20 MiB on the phone)')
    if not 0 <= args.kills <= len(KILL_DELAYS):
        raise ValueError(f'--kills must be 0..{len(KILL_DELAYS)}')
    if not 0 <= args.seed <= 1_000_000:
        raise ValueError('--seed out of range')
    for name in ('edits', 'trashes', 'deletes', 'desktop_links'):
        if getattr(args, name) is not None and not 0 <= getattr(args, name) <= 1000:
            raise ValueError(f'--{name.replace("_", "-")} out of range')
    for scenario in (SCENARIOS if args.scenario == 'all' else (args.scenario,)):
        p = params(args, scenario)
        if p['tie'] > p['links'] or p['tie'] > p['desktop_links']:
            raise ValueError('tie must not exceed links or desktop links')
        if scenario == 'delete_edit' and p['tie'] + args.edits + args.trashes + args.deletes > p['links']:
            raise ValueError('not enough links for --edits/--trashes/--deletes')
    out = Path(args.output).resolve() if args.output else None
    if out is not None and not (out == Path('/tmp') or Path('/tmp') in out.parents):
        raise ValueError('--output must be under /tmp')


def params(args, scenario):
    tie = args.tie if args.tie is not None else (min(5, args.links) if scenario == 'tie_review' else 0)
    desktop = max(args.desktop_links if args.desktop_links is not None else 3, tie)
    return {'scenario': scenario, 'links': args.links, 'previews': args.previews, 'tie': tie, 'desktop_links': desktop, 'seed': args.seed}


def plan(args):
    lines = ['target phone: ' + SERIAL + ' (test phone), package ' + PACKAGE + ', adb only: am instrument / am force-stop / run-as sync-lan-*']
    for scenario in (SCENARIOS if args.scenario == 'all' else (args.scenario,)):
        p = params(args, scenario)
        lines.append(f'[{scenario}] fresh replica sync-lan-<id>; desktop library with {p["desktop_links"]} synthetic links; pairing (identity, adopt, enroll, peer-enroll)')
        lines.append(f'[{scenario}] phone seed: {p["links"]} links, {p["previews"]} x 20 MiB previews, {p["tie"]} tied with the computer')
        if scenario == 'first_sync':
            lines += [f'[{scenario}] Sync now (timed, limit {FIRST_SYNC_LIMIT_S} s), compare, Sync again (no change)']
        elif scenario == 'tie_review':
            lines += [f'[{scenario}] Sync now, expect {p["tie"]} tie reviews, resolve per link (alternating), Sync, compare both sides']
        elif scenario == 'delete_edit':
            lines += [f'[{scenario}] Sync, then rename {args.edits}, trash {args.trashes}, delete {args.deletes} on the phone, Sync, compare']
        else:
            lines += [f'[{scenario}] force-stop after seed, inspect; {args.kills} syncs force-stopped at {list(KILL_DELAYS[:args.kills])} s after sync-start, inspect each time; final Sync, compare']
        lines.append(f'[{scenario}] remove the replica folder on the phone' + (' (kept: --keep)' if args.keep else ''))
    lines.append('summary: counts, timings, pass/fail per check (no hashes, no keys); written to the output folder')
    return lines


# ---------------------------------------------------------------- comparison (pure)

def by_id(dump):
    return {row['link_id']: row for row in dump['links']}


def expect_seeded(p, phone, desk, problems, deleted=(), trashed=(), edited=()):
    """Every phone-seeded link must be the same on both sides (shape of `inspect` and `link_lan dump`)."""
    ph, dk = by_id(phone), by_id(desk)
    for i in range(1, p['links'] + 1):
        lid = phone_link_id(i, p['tie'])
        if i in deleted:
            if lid in dk:
                problems.append(f'link {i} was deleted on the phone but is still on the computer')
            continue
        if lid not in ph:
            problems.append(f'link {i} missing on the phone')
            continue
        if lid not in dk:
            problems.append(f'link {i} missing on the computer')
            continue
        a, b = ph[lid], dk[lid]
        if a['title'] != b['title']:
            problems.append(f'link {i} title differs')
        if a['text_sha'] != b['text_sha']:
            problems.append(f'link {i} text differs')
        if a['preview_sha'] != b['preview_sha'] or a['preview_len'] != b['preview_len']:
            problems.append(f'link {i} preview differs')
        if i in edited and b['title'] != f'Edited link {i}':
            problems.append(f'link {i} edit did not arrive')
        if i in trashed and b['state'] != 'trashed':
            problems.append(f'link {i} trash did not arrive')
        if i <= p['previews'] and i > p['tie'] and b['preview_len'] != PREVIEW_BYTES:
            problems.append(f'link {i} preview is not 20 MiB on the computer')


def expect_counts(p, phone, desk, problems, minus=0):
    want = p['links'] + (p['desktop_links'] - p['tie']) - minus
    if phone['count'] != want:
        problems.append(f'phone holds {phone["count"]} links, expected {want}')
    if desk['count'] != want:
        problems.append(f'computer holds {desk["count"]} links, expected {want}')
    for dl in range(p['tie'] + 1, p['desktop_links'] + 1):
        if link_id(7000 + dl) not in by_id(phone):
            problems.append(f'computer link {dl} did not reach the phone')
        if link_id(7000 + dl) not in by_id(desk):
            problems.append(f'computer link {dl} disappeared')


def expect_unique(dump, problems, label):
    ids = [row['link_id'] for row in dump['links']]
    if len(ids) != len(set(ids)):
        problems.append(f'{label}: duplicate link ids')


def same_data(before, after, problems, label):
    """No data lost across a kill: every earlier link is still there with the same text and preview."""
    after_ids = by_id(after)
    for lid, row in by_id(before).items():
        now = after_ids.get(lid)
        if now is None:
            problems.append(f'{label}: a link was lost')
        elif (now['text_sha'], now['preview_sha'], now['preview_len']) != (row['text_sha'], row['preview_sha'], row['preview_len']):
            problems.append(f'{label}: a link changed')


# ---------------------------------------------------------------- phone

SHELL_OK = (
    re.compile(r'am instrument -w -r( -e [A-Za-z]+ [A-Za-z0-9_.:/-]+)* ' + re.escape(INSTRUMENT)),
    re.compile(r'am force-stop ' + re.escape(PACKAGE)),
    re.compile(r'run-as ' + re.escape(PACKAGE) + r' (cat|rm -rf|mkdir -p) (files/checks/sync-lan-[a-z0-9-]+(/files(/[a-z.-]+)?)?)'),
    re.compile(r'run-as ' + re.escape(PACKAGE) + r" sh -c 'cat > files/checks/sync-lan-[a-z0-9-]+/files/[a-z.-]+'"),
    re.compile(r'getprop ro\.product\.(device|model)'),
    re.compile(r'pm list instrumentation'),
    re.compile(r'ps -A -o ARGS'),
)


def shell_allowed(command):
    return any(p.fullmatch(command) for p in SHELL_OK)


class Phone:
    def __init__(self, adb, port, runner=interop.real_runner):
        if not isinstance(port, int):
            raise Refused('an explicit --adb-port is required')
        self.base = [str(adb), '-P', str(port), '-s', SERIAL]
        self.runner = runner
        self.guard = interop.Adb(runner, adb, port)

    def _argv(self, tail):
        argv = self.base + tail
        if any(DENIED in a for a in argv) or argv[3:5] != ['-s', SERIAL]:
            raise Refused('device selection guard failed')
        return argv

    def shell(self, *parts, data=None, timeout=600):
        command = shlex.join(parts)
        if not shell_allowed(command):
            raise Refused('adb shell command not allowed: ' + command[:160])
        return self.runner(self._argv(['shell', '-T', command]), input=data, env=None, cwd=None, timeout=timeout)

    def popen(self, *parts):
        command = shlex.join(parts)
        if not shell_allowed(command):
            raise Refused('adb shell command not allowed: ' + command[:160])
        return subprocess.Popen(self._argv(['shell', '-T', command]), stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

    def read(self, relative):
        if not PHONE_FILE.fullmatch(relative):
            raise Refused('phone path outside the replica files')
        r = self.runner(self._argv(['exec-out', 'run-as', PACKAGE, 'cat', relative]), input=None, env=None, cwd=None, timeout=120)
        if r.rc != 0:
            raise RuntimeError('could not read a phone file')
        return r.out

    def put(self, relative, data):
        if not PHONE_FILE.fullmatch(relative):
            raise Refused('phone path outside the replica files')
        self.shell('run-as', PACKAGE, 'sh', '-c', f'cat > {relative}', data=data)
        if self.read(relative) != data:
            raise RuntimeError('read-back mismatch on a phone file')

    def instrument_args(self, extra):
        parts = ['am', 'instrument', '-w', '-r']
        for key, value in extra:
            parts += ['-e', key, str(value)]
        return parts + [INSTRUMENT]

    @staticmethod
    def parse(text, label):
        if 'FAIL' in text or 'INSTRUMENTATION_CODE: -1' not in text:
            raise RuntimeError('phone ' + label + ' failed: ' + scrub(text[-300:]).replace('\n', ' '))
        m = re.search(r'PASS \d+: ' + re.escape(label) + r': (\{[^\n]*\})', text)
        if not m:
            raise RuntimeError('phone ' + label + ' printed no result')
        return json.loads(m.group(1))

    def transport(self, name, phase, **kv):
        text = self.shell(*self.instrument_args([('transportPhase', phase), ('replicaName', name)] + list(kv.items()))).out.decode(errors='replace')
        return self.parse(text, 'transport ' + phase)

    def lan(self, name, phase, **kv):
        extra = [('linkLan', 'true'), ('linkLanPhase', phase), ('replicaName', name), ('disposableDevice', SERIAL)] + list(kv.items())
        text = self.shell(*self.instrument_args(extra)).out.decode(errors='replace')
        return self.parse(text, 'link-lan ' + phase)

    def lan_async(self, name, phase, **kv):
        extra = [('linkLan', 'true'), ('linkLanPhase', phase), ('replicaName', name), ('disposableDevice', SERIAL)] + list(kv.items())
        return self.popen(*self.instrument_args(extra))

    def marker(self, name):
        try:
            return self.read(f'files/checks/{name}/files/link-lan-phase').decode().strip()
        except RuntimeError:
            return ''

    def force_stop(self):
        self.shell('am', 'force-stop', PACKAGE)

    def remove(self, name):
        if not REPLICA.fullmatch(name):
            raise Refused('bad replica name')
        self.shell('run-as', PACKAGE, 'rm', '-rf', f'files/checks/{name}')


# ---------------------------------------------------------------- desktop

class Desktop:
    def __init__(self, work, cli, transport, helper):
        self.cli, self.transport, self.helper = str(cli), str(transport), str(helper)
        home = work / 'home'
        home.mkdir(mode=0o700, exist_ok=True)
        self.env = {'PATH': os.environ.get('PATH', '/usr/bin:/bin'), 'HOME': str(home), 'XDG_CONFIG_HOME': str(home / 'c'),
                    'XDG_DATA_HOME': str(home / 'd'), 'XDG_CACHE_HOME': str(home / 'k'), 'XDG_STATE_HOME': str(home / 's'),
                    'DBUS_SESSION_BUS_ADDRESS': 'unix:path=/nonexistent'}

    def json(self, *argv, timeout=300):
        out = subprocess.run([str(a) for a in argv], env=self.env, capture_output=True, text=True, timeout=timeout)
        if out.returncode != 0:
            raise RuntimeError('desktop command failed: ' + scrub(out.stderr[-200:]).replace('\n', ' '))
        return json.loads(out.stdout)

    def make(self, lib, links):
        return self.json(self.helper, 'make', lib, links)

    def dump(self, lib):
        return self.json(self.helper, 'dump', lib)

    def listener(self, lib, host):
        with socket.socket() as probe:
            probe.bind((host, 0))
            port = probe.getsockname()[1]
        server = subprocess.Popen([self.transport, 'listen-once', str(lib), f'{host}:{port}'], env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        deadline = time.monotonic() + 15
        while True:
            if server.poll() is not None:
                raise RuntimeError('desktop listener exited early')
            if any(line.split()[1].endswith(f':{port:04X}') and line.split()[3] == '0A'
                   for line in Path(f'/proc/{server.pid}/net/tcp').read_text().splitlines()[1:]):
                return server, port
            if time.monotonic() > deadline:
                server.kill()
                raise RuntimeError('desktop listener not ready')
            time.sleep(0.05)


def lan_address():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(('192.0.2.1', 9))   # no packet is sent; this only selects the LAN interface
        return s.getsockname()[0]


# ---------------------------------------------------------------- the run

class Run:
    def __init__(self, args, phone, desktop, work, host):
        self.args, self.phone, self.desktop, self.work, self.host = args, phone, desktop, work, host
        self.timings, self.checks = {}, []
        self.name = self.lib = self.group = self.peer = self.phone_replica = None

    def check(self, label, problems):
        self.checks.append({'check': label, 'ok': not problems, 'problems': [scrub(x) for x in problems[:5]]})

    def timed(self, label, fn):
        start = time.monotonic()
        result = fn()
        self.timings[label] = round(time.monotonic() - start, 2)
        return result

    # pairing: the existing transport phases of PairingTransportChecks, then link records on both sides
    def pair(self, p):
        self.name = 'sync-lan-' + uuid.uuid4().hex[:12]
        files = f'files/checks/{self.name}/files'
        self.phone.shell('run-as', PACKAGE, 'mkdir', '-p', files)
        identity = self.phone.transport(self.name, 'identity')
        self.phone_replica = identity['replica']
        cert = self.phone.read(f'{files}/lan-identity.der')
        if hashlib.sha256(cert).hexdigest() != identity['fingerprint']:
            raise RuntimeError('phone identity does not match its fingerprint')
        cert_path = self.work / f'{self.name}-phone.der'
        cert_path.write_bytes(cert)
        self.lib = self.work / f'{self.name}-desktop'
        self.desktop.make(self.lib, p['desktop_links'])
        state = self.desktop.json(self.desktop.cli, 'sync', self.lib, 'start', self.phone_replica)
        self.group, self.peer = state['library'], state['replica']
        self.desktop.json(self.desktop.transport, 'identity-init', self.lib)
        peer_cert = (self.lib / 'pairing/identity.der').read_bytes()
        pin = hashlib.sha256(peer_cert).hexdigest()
        self.phone.put(f'{files}/lan-peer.der', peer_cert)
        baseline = self.work / f'{self.name}-baseline.stackexchange'
        self.desktop.json(self.desktop.cli, 'sync', self.lib, 'export', self.phone_replica, baseline)
        self.phone.put(f'{files}/lan-baseline.stackexchange', baseline.read_bytes())
        self.phone.transport(self.name, 'adopt', group=self.group, peer=self.peer)
        self.phone.transport(self.name, 'enroll', group=self.group, peer=self.peer, fingerprint=pin)
        self.desktop.json(self.desktop.transport, 'peer-enroll', self.lib, self.group, self.phone_replica, cert_path, identity['fingerprint'])

    def seed(self, p):
        return self.timed('seed_s', lambda: self.phone.lan(self.name, 'seed', links=p['links'], previews=p['previews'], tie=p['tie'], seed=p['seed']))

    def sync(self, label):
        """One Sync now over the LAN: the desktop listens once, the phone connects. Returns the phone's result."""
        server, port = self.desktop.listener(self.lib, self.host)
        try:
            result = self.timed(label, lambda: self.phone.lan(self.name, 'sync', host=self.host, port=port))
            _, err = server.communicate(timeout=120)
            if server.returncode != 0:
                raise RuntimeError('desktop side of the session failed: ' + scrub(err[-200:]).replace('\n', ' '))
            return result
        finally:
            if server.poll() is None:
                server.kill()

    def killed_sync(self, delay):
        """A Sync that is force-stopped `delay` seconds after the phone wrote sync-start."""
        server, port = self.desktop.listener(self.lib, self.host)
        proc = self.phone.lan_async(self.name, 'sync', host=self.host, port=port)
        try:
            deadline = time.monotonic() + 30
            while not self.phone.marker(self.name).startswith('sync-start'):
                if time.monotonic() > deadline or proc.poll() is not None:
                    break
                time.sleep(0.1)
            time.sleep(delay)
            finished = self.phone.marker(self.name).startswith('sync-done')
            self.phone.force_stop()
            return finished
        finally:
            try:
                proc.wait(timeout=30)
            except subprocess.TimeoutExpired:
                proc.kill()
            if server.poll() is None:
                server.terminate()
            try:
                server.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                server.kill()

    def inspect(self):
        return self.phone.lan(self.name, 'inspect')

    def converge(self, p, label, **kw):
        phone, desk = self.inspect(), self.desktop.dump(self.lib)
        problems = []
        expect_counts(p, phone, desk, problems, minus=kw.get('minus', 0))
        expect_seeded(p, phone, desk, problems, deleted=kw.get('deleted', ()), trashed=kw.get('trashed', ()), edited=kw.get('edited', ()))
        expect_unique(phone, problems, 'phone')
        expect_unique(desk, problems, 'computer')
        if phone['ties']:
            problems.append(f'{len(phone["ties"])} tie reviews still open')
        self.check(label, problems)
        return phone, desk

    # ---- scenarios
    def first_sync(self, p):
        self.seed(p)
        first = self.sync('first_sync_s')
        self.checks.append({'check': f'first sync within {FIRST_SYNC_LIMIT_S} s', 'ok': self.timings['first_sync_s'] < FIRST_SYNC_LIMIT_S, 'problems': []})
        before, _ = self.converge(p, 'after first sync: counts, titles, text and preview hashes match')
        second = self.sync('second_sync_s')
        after, _ = self.converge(p, 'after the no-change sync: still matching')
        problems = []
        if (after['operations'], after['count']) != (before['operations'], before['count']):
            problems.append('the no-change sync changed the phone')
        self.check('no-change sync is clean', problems)
        return before

    def tie_review(self, p):
        self.seed(p)
        self.sync('first_sync_s')
        phone = self.inspect()
        problems = []
        if len(phone['ties']) != p['tie']:
            problems.append(f'expected {p["tie"]} tie reviews, saw {len(phone["ties"])}')
        self.check('tie reviews appear, one per tied link', problems)
        decided = self.timed('resolve_s', lambda: self.phone.lan(self.name, 'resolve', side='mixed'))
        problems = []
        if decided['resolved'] != p['tie'] or decided['remaining'] != 0:
            problems.append('tie reviews were not all resolved')
        self.check('every tie resolved per link (alternating phone / computer)', problems)
        self.sync('second_sync_s')
        phone, desk = self.inspect(), self.desktop.dump(self.lib)
        problems = []
        ph, dk = by_id(phone), by_id(desk)
        for i in range(1, p['tie'] + 1):
            want = f'Phone link {i}' if (i - 1) % 2 == 0 else f'Desktop link {i}'
            lid = link_id(7000 + i)
            if ph.get(lid, {}).get('title') != want or dk.get(lid, {}).get('title') != want:
                problems.append(f'tied link {i} did not converge on the chosen side')
        if phone['ties']:
            problems.append('tie reviews reappeared after the sync')
        expect_counts(p, phone, desk, problems)
        self.check('both sides hold the chosen version of each tied link', problems)

    def delete_edit(self, p):
        a = self.args
        self.first_sync(p)
        self.phone.lan(self.name, 'edit', links=p['links'], tie=p['tie'], edits=a.edits, trashes=a.trashes, deletes=a.deletes)
        self.sync('change_sync_s')
        t = p['tie']
        edited = set(range(t + 1, t + 1 + a.edits))
        trashed = set(range(t + 1 + a.edits, t + 1 + a.edits + a.trashes))
        deleted = set(range(t + 1 + a.edits + a.trashes, t + 1 + a.edits + a.trashes + a.deletes))
        phone, desk = self.inspect(), self.desktop.dump(self.lib)
        problems = []
        expect_seeded(p, phone, desk, problems, deleted=deleted, trashed=trashed, edited=edited)
        expect_counts(p, phone, desk, problems, minus=len(deleted))
        self.check('edits, trashes and deletes reached the computer', problems)
        self.sync('final_sync_s')
        problems = []
        again = self.desktop.dump(self.lib)
        if again['count'] != desk['count']:
            problems.append('a further sync changed the computer')
        self.check('a further sync is clean (deleted links stay deleted)', problems)

    def kill(self, p):
        self.seed(p)
        before = self.inspect()
        self.phone.force_stop()
        after = self.inspect()
        problems = []
        same_data(before, after, problems, 'kill after seed')
        if after['count'] != before['count']:
            problems.append('count changed')
        self.check('kill between seed and sync loses nothing', problems)
        for k in range(self.args.kills):
            delay = KILL_DELAYS[k]
            finished = self.killed_sync(delay)
            now = self.inspect()
            problems = []
            same_data(before, now, problems, f'kill {k + 1}')
            expect_unique(now, problems, f'kill {k + 1}')
            self.check(f'kill {k + 1} inside a sync (+{delay} s{", sync had already finished" if finished else ""}): no data lost', problems)
        self.first_sync_after_kills(p)

    def first_sync_after_kills(self, p):
        self.sync('first_sync_s')
        self.checks.append({'check': f'final sync after the kills within {FIRST_SYNC_LIMIT_S} s', 'ok': self.timings['first_sync_s'] < FIRST_SYNC_LIMIT_S, 'problems': []})
        self.converge(p, 'final sync after the kills: everything matches')
        self.sync('second_sync_s')
        self.converge(p, 'no-change sync after the kills: still matching')

    def execute(self, scenario):
        p = params(self.args, scenario)
        try:
            self.pair(p)
            getattr(self, scenario)(p)
        except Exception as error:   # one scenario failing must not hide the others
            self.checks.append({'check': 'run completed', 'ok': False, 'problems': [scrub(f'{type(error).__name__}: {error}')[:300]]})
        finally:
            if self.name and not self.args.keep:
                try:
                    self.phone.force_stop()
                    self.phone.remove(self.name)
                except Exception:
                    pass
        return {'scenario': scenario, 'links': p['links'], 'previews': p['previews'], 'tie': p['tie'], 'desktop_links': p['desktop_links'],
                'timings_s': self.timings, 'checks': self.checks, 'passed': all(c['ok'] for c in self.checks)}


def build(env_home):
    env = {**os.environ, 'CARGO_HOME': str(env_home)}
    for cmd in (['cargo', 'build', '--offline', '-p', 'stack-cli', '--example', 'link_lan'], ['cargo', 'build', '--offline', '-p', 'stack-cli'],
                ['cargo', 'build', '--offline', '-p', 'stack-transport']):
        subprocess.run(cmd, cwd=REPO / 'desktop', env=env, check=True, capture_output=True)   # one at a time, never two cargo builds at once


# ---------------------------------------------------------------- selftest

def selftest():
    def ns(**kw):
        base = dict(serial=SERIAL, package=PACKAGE, scenario='first_sync', links=50, previews=5, tie=None, desktop_links=None, kills=3, seed=1,
                    edits=5, trashes=3, deletes=5, output=None, keep=False, adb=None, adb_port=5037, build=False, dry_run=False, host=None, cli=None,
                    transport=None, helper=None, selftest=False)
        base.update(kw)
        return argparse.Namespace(**base)

    def refuses(exc, **kw):
        try:
            check_args(ns(**kw), env={})
        except exc:
            return True
        return False
    check_args(ns(), env={})
    assert refuses(Refused, serial=DENIED) and refuses(Refused, serial='ZZZ') and refuses(Refused, package='org.stackdocs.app')
    try:
        check_args(ns(), env={'ANDROID_SERIAL': DENIED})
        raise AssertionError('production ANDROID_SERIAL accepted')
    except Refused:
        pass
    assert refuses(ValueError, links=0) and refuses(ValueError, links=1001) and refuses(ValueError, previews=51, links=100)
    assert refuses(ValueError, scenario='nope') and refuses(ValueError, output='/home/x') and refuses(ValueError, kills=9)
    assert refuses(ValueError, scenario='delete_edit', links=10, edits=5, trashes=3, deletes=5)
    check_args(ns(links=1000, previews=0, scenario='all'), env={})
    assert link_id(7001) == '00000000-0000-0000-0000-000000001b59' and phone_link_id(2, 3) == link_id(7002) and phone_link_id(4, 3) == link_id(900004)
    assert all(shell_allowed(c) for c in ('am force-stop ' + PACKAGE, 'pm list instrumentation',
                                           'run-as ' + PACKAGE + ' rm -rf files/checks/sync-lan-abc123'))
    for bad in ('am force-stop org.stackdocs.app', 'pm clear ' + PACKAGE, 'pm uninstall ' + PACKAGE, 'am start -n x/y', 'run-as ' + PACKAGE + ' rm -rf files',
                'run-as org.stackdocs.app cat files/checks/sync-lan-a/files/lan-peer.der', 'run-as ' + PACKAGE + ' cat ../x'):
        assert not shell_allowed(bad), bad
    assert all(len(plan(ns(scenario=s))) > 3 for s in SCENARIOS + ('all',))
    # comparison logic on synthetic dumps
    p = params(ns(links=4, previews=1, tie=0), 'first_sync')

    def row(i, **kw):
        r = {'link_id': phone_link_id(i, 0), 'title': f'Synthetic link {i}', 'state': 'live', 'origin': 'own', 'text_sha': 't%d' % i,
             'preview_sha': 'p' if i == 1 else '', 'preview_len': PREVIEW_BYTES if i == 1 else 0}
        r.update(kw)
        return r
    extra = [{'link_id': link_id(7000 + n), 'title': f'Desktop link {n}', 'state': 'live', 'origin': 'own', 'text_sha': 'd', 'preview_sha': '', 'preview_len': 0} for n in (1, 2, 3)]
    good = {'count': 7, 'links': [row(i) for i in range(1, 5)] + extra, 'ties': []}
    problems = []
    expect_counts(p, good, good, problems)
    expect_seeded(p, good, good, problems)
    assert not problems, problems
    bad = {'count': 6, 'links': [row(i) for i in range(1, 4)] + extra, 'ties': []}
    problems = []
    expect_counts(p, good, bad, problems)
    expect_seeded(p, good, bad, problems)
    assert len(problems) >= 2, problems
    problems = []
    same_data(good, bad, problems, 'x')
    assert problems
    assert 'hex64' in scrub('a' * 64) and 'a' * 64 not in scrub('a' * 64)
    print('link-lan selftest: ok')
    return 0


# ---------------------------------------------------------------- main

def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--scenario', default='first_sync', help='first_sync | tie_review | delete_edit | kill | all')
    ap.add_argument('--links', type=int, default=50, help='synthetic links seeded on the phone (1..1000, default 50)')
    ap.add_argument('--previews', type=int, default=5, help='how many of them carry a 20 MiB preview (default 5)')
    ap.add_argument('--tie', type=int, default=None, help='links that also exist on the computer with the same modified time (default 5 for tie_review, else 0)')
    ap.add_argument('--desktop-links', type=int, default=None, help='synthetic links on the computer before pairing (default 3)')
    ap.add_argument('--kills', type=int, default=3, help='kill scenario: syncs force-stopped inside the session (0..5)')
    ap.add_argument('--edits', type=int, default=5)
    ap.add_argument('--trashes', type=int, default=3)
    ap.add_argument('--deletes', type=int, default=5)
    ap.add_argument('--seed', type=int, default=1)
    ap.add_argument('--serial', default=SERIAL, help='only the test phone ' + SERIAL + ' is accepted')
    ap.add_argument('--package', default=PACKAGE)
    ap.add_argument('--adb', type=Path, default=ADB_DEFAULT)
    ap.add_argument('--adb-port', type=int, default=5037)
    ap.add_argument('--host', help='this computer\'s LAN address (default: detected)')
    ap.add_argument('--cli', type=Path, default=BIN / 'stack-cli')
    ap.add_argument('--transport', type=Path, default=BIN / 'stack-transport')
    ap.add_argument('--helper', type=Path, default=BIN / 'examples/link_lan')
    ap.add_argument('--build', action='store_true', help='build the desktop binaries first (offline); otherwise nothing is built')
    ap.add_argument('--output', help='fresh folder under /tmp (default: a new one)')
    ap.add_argument('--keep', action='store_true', help='leave the replica folder on the phone for inspection')
    ap.add_argument('--dry-run', action='store_true', help='validate the arguments and list what would run; no adb, no files')
    ap.add_argument('--selftest', action='store_true', help='host checks of the guards and comparisons; no phone')
    args = ap.parse_args(argv)
    if args.selftest:
        return selftest()
    try:
        check_args(args)
    except Refused as e:
        print('REFUSED: ' + str(e), file=sys.stderr)
        return 2
    except ValueError as e:
        ap.error(str(e))
    if args.dry_run:
        print('link-lan dry run (nothing touched):')
        for line in plan(args):
            print('  ' + line)
        return 0
    if args.build:
        build(Path.home() / '.cache/stack-cargo-home')
    for tool in (args.cli, args.transport, args.helper, args.adb):
        if not Path(tool).is_file():
            print(f'missing {Path(tool).name}: run with --build (desktop) or check the adb path', file=sys.stderr)
            return 2
    phone = Phone(args.adb, args.adb_port)
    try:
        phone.guard.preflight()   # serial, Pixel 4a, checks instrumentation installed, no other instrumentation
    except interop.Refused as e:
        print('REFUSED: ' + str(e), file=sys.stderr)
        return 2
    stamp = datetime.datetime.now().strftime('%Y%m%d-%H%M%S')
    out = Path(args.output) if args.output else Path('/tmp') / f'stack-link-lan-{stamp}'
    out.mkdir(mode=0o700)
    host = args.host or lan_address()
    results = []
    for scenario in (SCENARIOS if args.scenario == 'all' else (args.scenario,)):
        work = out / scenario
        work.mkdir(mode=0o700)
        desktop = Desktop(work, args.cli, args.transport, args.helper)
        results.append(Run(args, phone, desktop, work, host).execute(scenario))
    summary = {'date': stamp, 'phone': 'test phone', 'results': results, 'passed': all(r['passed'] for r in results)}
    (out / 'summary.json').write_text(scrub(json.dumps(summary, indent=2)) + '\n')
    for r in results:
        print(f'{"PASS" if r["passed"] else "FAIL"} {r["scenario"]}: {r["links"]} links, {r["previews"]} previews; '
              + ', '.join(f'{k} {v}' for k, v in r['timings_s'].items()))
        for c in r['checks']:
            if not c['ok']:
                print('  FAIL ' + c['check'] + (': ' + '; '.join(c['problems']) if c['problems'] else ''))
    print('Summary: ' + str(out / 'summary.json'))
    return 0 if summary['passed'] else 1


if __name__ == '__main__':
    sys.exit(main())
