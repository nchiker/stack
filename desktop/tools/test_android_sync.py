#!/usr/bin/env python3
"""Exercise production Android/Rust sync in a separately installed Checks app.

Does not install, uninstall, reset, unlock, or open activities. Each Android
mutation is confined to a new synthetic checks/sync-* directory. Requires an
explicit device, ADB server port, and noncanonical Checks namespace.
"""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import shlex
import shutil
import socket
import ssl
import struct
import subprocess
import time
import threading
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True, type=Path)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--adb-port', required=True, type=int)
    parser.add_argument('--package', required=True)
    parser.add_argument('--cli', required=True, type=Path)
    parser.add_argument('--fixtures', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--transport', type=Path)
    parser.add_argument('--host', help='Explicit host LAN address, required with --transport')
    parser.add_argument('--lan-only', action='store_true', help='Run only missing paired-transport evidence')
    parser.add_argument('--recovery', action='store_true', help='Also check cancellation, wrong-key, corruption and reconnect')
    args = parser.parse_args()
    if bool(args.transport) != bool(args.host):
        parser.error('--transport and --host are required together')
    if args.lan_only and not args.transport:
        parser.error('--lan-only requires --transport and --host')
    if args.recovery and not args.transport:
        parser.error('--recovery requires --transport and --host')
    if not re.fullmatch(r'org\.stackdocs\.app\.checks\.[a-z][a-z0-9_]{0,31}', args.package):
        parser.error('A separate Checks namespace is required')
    output = args.output.resolve()
    if not output.is_relative_to('/tmp'):
        parser.error('Choose a fresh disposable /tmp output')
    if json.loads((args.fixtures / 'synthetic-fixture.json').read_text()) != {
            'fixture': 'stack-m1', 'version': 1, 'license': 'CC0-1.0'}:
        parser.error('Authored M1 fixtures required')
    output.mkdir(mode=0o700)
    adb = [str(args.adb.resolve()), '-P', str(args.adb_port), '-s', args.serial]
    instrument = f'{args.package}.test/org.stackdocs.app.ScannerChecks'
    cli = str(args.cli.resolve())
    checks, transcripts = [], []
    name = 'sync-r5-' + uuid.uuid4().hex[:12]
    directory = f'files/checks/{name}/files'

    def shell(*parts, data=None):
        return subprocess.check_output([*adb, 'shell', '-T', shlex.join(parts)], input=data, timeout=180)

    def run(*parts):
        return json.loads(subprocess.check_output([cli, *map(str, parts)], text=True, timeout=180))

    def phone(phase, replica=name, transport=False, **values):
        kind = 'transport' if transport else 'sync'
        command = ['am', 'instrument', '-w', '-r', '-e', kind + 'Phase', phase,
                   '-e', 'replicaName', replica]
        for key, value in values.items():
            command += ['-e', key, str(value)]
        text = shell(*command, instrument).decode()
        transcripts.append({'replica': replica, 'phase': phase, 'output': text})
        (output / 'instrumentation.json').write_text(json.dumps(transcripts, indent=2) + '\n')
        assert 'FAIL after' not in text and 'INSTRUMENTATION_CODE: -1' in text, text
        match = re.search(r'PASS \d+: ' + kind + ' ' + re.escape(phase) + r': (\{[^\n]+\})', text)
        assert match, text
        return json.loads(match.group(1))

    def read(relative):
        return subprocess.check_output([*adb, 'exec-out', 'run-as', args.package, 'cat', relative], timeout=60)

    def deliver(path, replica=name):
        relative = f'files/checks/{replica}/files/inbox.stackexchange'
        put(relative, path.read_bytes())

    def put(relative, contents):
        shell('run-as', args.package, 'sh', '-c', f'cat > {shlex.quote(relative)}', data=contents)
        assert read(relative) == contents

    def android_export(peer, label):
        phone('export', peer=peer)
        path = output / f'{label}.stackexchange'
        path.write_bytes(read(directory + '/outbox.stackexchange'))
        return path

    def desktop_export(library, peer, label):
        path = output / f'{label}.stackexchange'
        run('sync', library, 'export', peer, path)
        deliver(path)
        return phone('import')

    def passed(label):
        checks.append(label)
        (output / 'progress.json').write_text(json.dumps(checks, indent=2) + '\n')

    listing = shell('pm', 'list', 'instrumentation').decode()
    assert f'{instrument} (target={args.package})' in listing, listing
    def file_checks():
        phone_id = phone('identity')['replica']
        library = output / 'rust-peer'
        peer = run('sync', library, 'status')['replica']
        seeded = phone('seed', peer=peer)
        group, document = seeded['library'], seeded['document']
        original_sha = seeded['original_sha256']
        baseline = output / 'android-baseline.stackexchange'
        baseline.write_bytes(read(directory + '/outbox.stackexchange'))
        run('sync', library, 'adopt', baseline, group, phone_id, peer)
        manifest = run('library', library, 'manifest')
        assert manifest['documents'][0]['title'] == 'Android original atlas'
        assert manifest['files'][0]['sha256'] == original_sha
        passed('Android seed and retained PDF baseline adopted by Rust')
        reopened = phone('inspect')
        phone('export', peer=peer)
        assert phone('inspect') == reopened
        passed('Repeated Android startup and export leave pending suggestions and causal state unchanged')

        # Each instrumentation invocation starts a new process and reopens SQLite.
        run('library', library, 'rename', document, 'Desktop concurrent title')
        phone('rename', document=document, title='Android concurrent title')
        run('sync', library, 'import', android_export(peer, 'android-conflict'))
        desktop_export(library, phone_id, 'desktop-conflict')
        status = phone('inspect')
        document_key = 'documents/' + json.dumps([document], separators=(',', ':'))
        key = document_key + '/title'
        alternatives = status['conflicts'][key]
        assert {item['value']['title'] for item in alternatives} == {
            'Desktop concurrent title', 'Android concurrent title'}
        rust_conflicts = run('sync', library, 'status')['conflicts']
        assert rust_conflicts[key] == alternatives
        passed('Concurrent titles survive both directions and process restart as the same conflict')
        choice = next(index for index, item in enumerate(alternatives)
                      if item['value']['title'] == 'Android concurrent title')
        remaining = phone('resolve', key=key, choice=choice)['conflicts']
        assert key not in remaining
        # Title/provenance and suggestion choices are separate decisions. Review
        # every remaining alternative explicitly rather than silently discarding it.
        for conflict_key, choices in list(remaining.items()):
            selected = next((index for index, item in enumerate(choices)
                             if item['id']['replica'] == phone_id), 0)
            remaining = phone('resolve', key=conflict_key, choice=selected)['conflicts']
            assert conflict_key not in remaining
        assert not remaining
        run('sync', library, 'import', android_export(peer, 'android-resolved'))
        assert not run('sync', library, 'status')['conflicts']
        assert run('library', library, 'manifest')['documents'][0]['title'] == 'Android concurrent title'
        passed('Reviewed Android conflict resolution converges on Rust')

        exact_notes = 'Desktop notes with /, emoji \U0001f4c4 and a newline\nSecond line.'
        run('library', library, 'notes', document, exact_notes)
        desktop_export(library, phone_id, 'desktop-notes')
        inspected = phone('inspect')
        assert inspected['documents'][0]['sha256'] == original_sha
        assert inspected['documents'][0]['notes'] == exact_notes
        phone_export = android_export(peer, 'android-notes-receipt')
        run('sync', library, 'import', phone_export)
        # Replay must preserve both the frontier and operation count.
        before = phone('inspect')
        deliver(output / 'desktop-notes.stackexchange')
        phone('import')
        assert phone('inspect') == before
        passed('Desktop notes import, receipt exchange and replay preserve original and causal state')

        # Rejected parser/partial-operation cases use the current reviewed group.
        raw = baseline.read_bytes()
        size = struct.unpack('>I', raw[8:12])[0]
        header = json.loads(raw[12:12 + size])
        hostile = []
        invalid_version = copy.deepcopy(header)
        invalid_version['version'] = 99
        invalid_cell = copy.deepcopy(header)
        operation = copy.deepcopy(header['operations'][0])
        operation['id']['sequence'] = 100
        operation['context'] = {phone_id: 99}
        operation['changes'] = {document_key + '/notes': {'notes': 42, 'notes_revision': 1}}
        invalid_cell['operations'] = [operation]
        for label, candidate in [('unsupported-version', invalid_version), ('malformed-gap-cell', invalid_cell)]:
            encoded = json.dumps(candidate, separators=(',', ':')).encode()
            hostile.append((label, raw[:8] + struct.pack('>I', len(encoded)) + encoded + raw[12 + size:]))
        hostile += [('truncated-blob', raw[:-1]), ('changed-blob', raw[:-1] + bytes([raw[-1] ^ 1]))]
        for label, contents in hostile:
            path = output / f'{label}.stackexchange'
            path.write_bytes(contents)
            deliver(path)
            assert phone('reject')['rejected']
        passed('Four hostile envelopes rejected without changing Android sync state')

        phone('delete', document=document)
        deletion = android_export(peer, 'android-deleted')
        run('sync', library, 'import', deletion)
        assert not run('library', library, 'manifest')['documents']
        assert not phone('inspect')['documents']
        assert hashlib.sha256(read(directory + f'/documents/{document}/source')).hexdigest() == original_sha
        deliver(baseline)
        phone('import')
        assert not phone('inspect')['documents']
        passed('Deletion survives old baseline replay and retains Android original bytes')

        # Opposite baseline direction uses an independently authored archive and a
        # fresh phone child root, so adoption never replaces an existing library.
        other = 'sync-r5-' + uuid.uuid4().hex[:12]
        other_id = phone('identity', replica=other)['replica']
        reverse = output / 'rust-source'
        run('archive', reverse, 'restore', args.fixtures.resolve() / 'format-3.stackbackup')
        started = run('sync', reverse, 'start', other_id)
        path = output / 'rust-baseline.stackexchange'
        run('sync', reverse, 'export', other_id, path)
        deliver(path, replica=other)
        phone('import', replica=other, group=started['library'], members=f'{started["replica"]},{other_id}')
        reverse_status = phone('inspect', replica=other)
        reverse_docs = reverse_status['documents']
        reverse_graph = run('library', reverse, 'manifest')
        assert {doc['id']: doc['title'] for doc in reverse_docs} == {
            doc['id']: doc['title'] for doc in reverse_graph['documents']}
        assert sorted(reverse_status['files'], key=lambda file: file['path']) == sorted(
            reverse_graph['files'], key=lambda file: file['path'])
        passed('Rust authored baseline adopted by a fresh Android library with matching originals')
        (output / 'r5-report.json').write_text(json.dumps({'status': 'passed', 'checks': checks,
                                                         'android_runtime': True}, indent=2) + '\n')
        return [name, other]

    replicas = [] if args.lan_only else file_checks()
    if args.transport:
        transport = str(args.transport.resolve())
        lan = 'sync-lan-' + uuid.uuid4().hex[:12]
        replicas.append(lan)
        lan_dir = f'files/checks/{lan}/files'
        lan_library = output / 'lan-rust'

        def paired(*parts):
            return json.loads(subprocess.check_output([transport, *map(str, parts)], text=True, timeout=90))

        identity = phone('identity', replica=lan, transport=True)
        lan_phone = identity['replica']
        phone_der = read(lan_dir + '/lan-identity.der')
        assert hashlib.sha256(phone_der).hexdigest() == identity['fingerprint']
        cert = output / 'phone-identity.der'
        cert.write_bytes(phone_der)
        run('archive', lan_library, 'restore', args.fixtures.resolve() / 'format-3.stackbackup')
        lan_state = run('sync', lan_library, 'start', lan_phone)
        lan_group, lan_peer = lan_state['library'], lan_state['replica']
        paired('identity-init', lan_library)
        peer_cert = (lan_library / 'pairing/identity.der').read_bytes()
        pin = hashlib.sha256(peer_cert).hexdigest()
        put(lan_dir + '/lan-peer.der', peer_cert)
        baseline = output / 'lan-baseline.stackexchange'
        run('sync', lan_library, 'export', lan_phone, baseline)
        put(lan_dir + '/lan-baseline.stackexchange', baseline.read_bytes())
        phone('adopt', replica=lan, transport=True, group=lan_group, peer=lan_peer)
        assert phone('wrong-fingerprint', replica=lan, transport=True, group=lan_group, peer=lan_peer)['rejected']
        failed = subprocess.run([transport, 'peer-enroll', str(lan_library), lan_group,
                                 lan_phone, str(cert), '0' * 64], capture_output=True, text=True)
        assert failed.returncode != 0 and not (lan_library / 'pairing/policy.json').exists()
        phone('enroll', replica=lan, transport=True, group=lan_group, peer=lan_peer, fingerprint=pin)
        paired('peer-enroll', lan_library, lan_group, lan_phone, cert, identity['fingerprint'])
        passed('Android Keystore public identity and reviewed fingerprint enrollment reject mismatches')
        lan_graph = run('library', lan_library, 'manifest')
        lan_document = lan_graph['documents'][0]['id']
        phone('rename', replica=lan, document=lan_document, title='Phone paired title')
        lan_notes = 'Paired desktop notes / exact\n\U0001f4c4'
        run('library', lan_library, 'notes', lan_document, lan_notes)
        def server_once(root=lan_library, phase='sync', label='lan-server'):
            # No probe connection: that would consume the one-shot listener.
            with socket.socket() as probe:
                probe.bind((args.host, 0))
                port = probe.getsockname()[1]
            server = subprocess.Popen([transport, 'listen-once', str(root), f'{args.host}:{port}'],
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            log = output / f'{label}.log'
            try:
                deadline = time.monotonic() + 10
                while True:
                    assert server.poll() is None, server.communicate()
                    listening = any(line.split()[1].endswith(f':{port:04X}') and line.split()[3] == '0A'
                                    for line in Path(f'/proc/{server.pid}/net/tcp').read_text().splitlines()[1:])
                    if listening:
                        break
                    assert time.monotonic() < deadline, 'Listener did not become ready'
                    time.sleep(0.05)
                result = phone(phase, replica=lan, transport=True, host=args.host, port=port)
                stdout, stderr = server.communicate(timeout=70)
                log.write_text(stdout + '\n' + stderr)
                assert server.returncode == 0 if phase == 'sync' else server.returncode != 0, stderr
                return result, port
            finally:
                if server.poll() is None:
                    server.kill()
                if not log.exists():
                    stdout, stderr = server.communicate()
                    log.write_text(stdout + '\n' + stderr)

        connected, port = server_once()
        assert not connected['status']['conflicts']
        lan_status = phone('inspect', replica=lan)
        received = next(doc for doc in lan_status['documents'] if doc['id'] == lan_document)
        assert received['title'] == 'Phone paired title' and received['notes'] == lan_notes
        rust_doc = next(doc for doc in run('library', lan_library, 'manifest')['documents']
                        if doc['id'] == lan_document)
        assert rust_doc['title'] == 'Phone paired title' and rust_doc['notes'] == lan_notes
        assert sorted(lan_status['files'], key=lambda file: file['path']) == sorted(
            lan_graph['files'], key=lambda file: file['path'])
        passed('Actual LAN Android Keystore mTLS exchanges offline edits in both directions and preserves originals')
        if args.recovery:
            assert phone('identity-reset-refused', replica=lan, transport=True)['rejected']
            passed('An active pairing identity cannot be replaced before explicit revocation')
            before = phone('inspect', replica=lan)
            with socket.socket() as blocked:
                blocked.bind((args.host, 0));blocked.listen(1);blocked.settimeout(10)
                blocked_port = blocked.getsockname()[1]
                observed = {}

                def hold_handshake():
                    try:
                        with blocked.accept()[0] as connection:
                            connection.settimeout(5)
                            observed['client_hello_bytes'] = len(connection.recv(65536))
                            put(lan_dir + '/cancel-signal', b'cancel')
                            while connection.recv(65536):
                                pass
                    except Exception as error:
                        observed['closed'] = str(error)

                worker = threading.Thread(target=hold_handshake, daemon=True)
                worker.start()
                canceled = phone('cancel', replica=lan, transport=True, host=args.host, port=blocked_port, waitForSignal='true')
                worker.join(8)
                assert not worker.is_alive() and observed.get('client_hello_bytes', 0) > 0
                assert canceled['canceled'] and canceled['elapsed_ms'] <= 5000
                (output / 'cancel.json').write_text(json.dumps({'phone': canceled, 'server': observed}, indent=2) + '\n')
            assert phone('inspect', replica=lan) == before
            passed('Foreground cancellation interrupts a blocked actual Android TLS handshake within five seconds')

            # Independently generated certificate on a synthetic copy with the
            # same group/replica is still an untrusted identity, and must fail.
            wrong = output / 'wrong-key-peer'
            shutil.copytree(lan_library, wrong, ignore=shutil.ignore_patterns('pairing'))
            paired('identity-init', wrong)
            paired('peer-enroll', wrong, lan_group, lan_phone, cert, identity['fingerprint'])
            rejected, _ = server_once(wrong, 'reject-session', 'wrong-key-server')
            assert rejected['rejected'] and phone('inspect', replica=lan) == before
            passed('A same-group/same-replica LAN endpoint with a different TLS identity is rejected')

            # Trusted synthetic TLS endpoint intentionally sends a corrupt
            # STACKEX1 payload. It uses only this run's generated private key.
            pem_cert, pem_key, pem_phone = output / 'server.pem', output / 'server-key.pem', output / 'phone.pem'
            subprocess.run(['openssl', 'x509', '-inform', 'DER', '-in', str(lan_library / 'pairing/identity.der'), '-out', str(pem_cert)], check=True)
            subprocess.run(['openssl', 'pkey', '-inform', 'DER', '-in', str(lan_library / 'pairing/identity-key.der'), '-out', str(pem_key)], check=True)
            pem_key.chmod(0o600)
            subprocess.run(['openssl', 'x509', '-inform', 'DER', '-in', str(cert), '-out', str(pem_phone)], check=True)
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            context.minimum_version = ssl.TLSVersion.TLSv1_2
            context.load_cert_chain(pem_cert, pem_key)
            context.verify_mode = ssl.CERT_REQUIRED
            context.load_verify_locations(pem_phone)
            magic = b'STACKLAN1'

            def exact(connection, length):
                parts = bytearray()
                while len(parts) < length:
                    chunk = connection.recv(length - len(parts))
                    assert chunk, 'Unexpected EOF'
                    parts.extend(chunk)
                return bytes(parts)

            def receive(connection, kind):
                frame = exact(connection, 18)
                assert frame[:9] == magic and frame[9:10] == kind
                length = struct.unpack('>Q', frame[10:])[0]
                assert length <= 32 * 1024 * 1024, 'Authored fixture bound'
                return exact(connection, length)

            def send(connection, kind, contents):
                connection.sendall(magic + kind + struct.pack('>Q', len(contents)) + contents)

            with socket.socket() as corrupt:
                corrupt.bind((args.host, 0));corrupt.listen(1);corrupt.settimeout(10)
                corrupt_port = corrupt.getsockname()[1]
                observed = {}

                def corrupt_bundle():
                    try:
                        with context.wrap_socket(corrupt.accept()[0], server_side=True) as connection:
                            connection.settimeout(10)
                            assert hashlib.sha256(connection.getpeercert(binary_form=True)).hexdigest() == identity['fingerprint']
                            hello = json.loads(receive(connection, b'H'))
                            # Current phones advertise their frontier; replying without one
                            # (as Stack 0.1.0 did) keeps the full session this check needs.
                            assert isinstance(hello.pop('frontier'), dict)
                            assert hello == {'version': 1, 'group': lan_group, 'replica': lan_phone}
                            send(connection, b'H', json.dumps({'version': 1, 'group': lan_group, 'replica': lan_peer}).encode())
                            observed['received_bundle_bytes'] = len(receive(connection, b'B'))
                            send(connection, b'R', b'{"durable_frontier":{}}')
                            broken = bytearray(baseline.read_bytes());broken[-1] ^= 1
                            send(connection, b'B', broken)
                            observed['corrupt_bundle_sent'] = True
                            while connection.recv(65536):
                                pass
                    except Exception as error:
                        observed['closed'] = str(error)

                worker = threading.Thread(target=corrupt_bundle, daemon=True)
                worker.start()
                rejected = phone('reject-session', replica=lan, transport=True, host=args.host, port=corrupt_port)
                worker.join(12)
                assert not worker.is_alive() and observed.get('corrupt_bundle_sent') and rejected['rejected'], observed
                (output / 'corrupt-server.json').write_text(json.dumps(observed, indent=2) + '\n')
            assert phone('inspect', replica=lan) == before
            passed('Authenticated LAN corruption is rejected without changing Android causal state or originals')
            connected, port = server_once(label='reconnect-server')
            assert phone('inspect', replica=lan) == before
            passed('Reconnect after cancellation, a wrong key and corruption confirms progress without duplicate edits')
        phone('revoke', replica=lan, transport=True)
        assert phone('revoked-reject', replica=lan, transport=True, host=args.host, port=port)['rejected']
        paired('peer-revoke', lan_library)
        assert phone('inspect', replica=lan)['documents'] == lan_status['documents']
        passed('Android and desktop revocation retain documents; Android rejects a later session')
    root = Path(__file__).resolve().parents[2]
    sources = [Path(__file__).resolve(), root / 'app/build.gradle']
    sources += list((root / 'app/src/main/java/org/stackdocs/app').glob('Sync*.java'))
    sources += [root / 'app/src/main/java/org/stackdocs/app' / file for file in
                ('PairingTransport.java', 'LibraryDatabase.java', 'LibraryRepository.java', 'LibraryArchive.java', 'SuggestionStore.java')]
    sources += [root / 'app/src/androidTest/java/org/stackdocs/app' / file for file in
                ('SyncDeviceChecks.java', 'PairingTransportChecks.java', 'ScannerChecks.java')]
    report = {'status': 'passed', 'checks': checks, 'android_runtime': True,
              'device': args.serial, 'package': args.package, 'replicas': replicas,
              'actual_lan': bool(args.transport),
              'source_sha256': {str(file.relative_to(root)): hashlib.sha256(file.read_bytes()).hexdigest()
                                for file in sources}}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
