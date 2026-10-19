#!/usr/bin/env python3
"""Headless FileChooser protocol + real app workers on a private session bus.

The test backend returns authored file URIs. This tests the pinned ashpd client,
request options, cancellation, URI conversion and durable import/export, without
opening a chooser/window. Human chooser interaction remains final review.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--worker', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    out = args.output.absolute()
    if not out.is_relative_to('/tmp'):
        parser.error('Use a fresh disposable /tmp root')
    os.umask(0o077)
    desktop = Path(__file__).resolve().parents[1]
    if not args.worker:
        out.mkdir(mode=0o700)
        env = dict(os.environ, STACK_M2_PORTAL_PROTOCOL=str(out),
                   STACK_PDFIUM_LIBRARY=str(desktop / 'runtime/pdfium/lib/libpdfium.so'),
                   RUSTUP_TOOLCHAIN='stable', CARGO_HOME='/tmp/stack-desktop-dev/cargo-home', CARGO_BUILD_JOBS='4')
        for key in ('DISPLAY', 'WAYLAND_DISPLAY', 'WAYLAND_DEBUG', 'AT_SPI_BUS_ADDRESS',
                    'SSH_AUTH_SOCK', 'GNOME_KEYRING_CONTROL', 'DBUS_SESSION_BUS_ADDRESS'):
            env.pop(key, None)
        for key in ('DATA', 'CONFIG', 'CACHE', 'STATE', 'RUNTIME'):
            folder = out / key.lower()
            folder.mkdir(mode=0o700)
            env['XDG_RUNTIME_DIR' if key == 'RUNTIME' else f'XDG_{key}_HOME'] = str(folder)
        subprocess.run(['dbus-run-session', '--', sys.executable, str(Path(__file__).resolve()),
                        '--worker', '--output', str(out)], env=env, check=True, timeout=180)
        print((out / 'report.json').read_text())
        return

    assert os.environ['STACK_M2_PORTAL_PROTOCOL'] == str(out)
    assert 'DISPLAY' not in os.environ and 'WAYLAND_DISPLAY' not in os.environ
    import dbus
    import dbus.service
    from dbus.mainloop.glib import DBusGMainLoop
    from gi.repository import GLib
    DBusGMainLoop(set_as_default=True)
    bus = dbus.SessionBus()
    name = dbus.service.BusName('org.freedesktop.portal.Desktop', bus=bus)
    requests = []
    objects = []
    failures = []

    class Request(dbus.service.Object):
        @dbus.service.signal('org.freedesktop.portal.Request', signature='ua{sv}')
        def Response(self, code, result):
            pass

        @dbus.service.method('org.freedesktop.portal.Request')
        def Close(self):
            pass

    class Portal(dbus.service.Object):
        @dbus.service.method('org.freedesktop.DBus.Properties', in_signature='ss', out_signature='v')
        def Get(self, interface, prop):
            assert (interface, prop) == ('org.freedesktop.portal.FileChooser', 'version')
            return dbus.UInt32(4, variant_level=1)

        def choose(self, method, parent, title, options, sender):
            index = len(requests)
            expected = json.loads((out / 'requests.json').read_text())[index]
            try:
                assert method == expected['method'] and title == expected['title']
                assert bool(options.get('multiple', False)) == expected.get('multiple', False)
                assert bool(options.get('modal', False))
                assert len(options['filters']) == 1
                token = str(options['handle_token'])
                assert token and '/' not in token
                path = '/org/freedesktop/portal/desktop/request/' + sender[1:].replace('.', '_') + '/' + token
                request = Request(bus, path)
                objects.append(request)
                uris = [Path(p).as_uri() for p in expected.get('paths', [])]
                requests.append({'method': method, 'title': title, 'multiple': bool(options.get('multiple', False)),
                                 'uri_count': len(uris), 'parented': bool(parent)})
                def respond():
                    request.Response(1 if expected.get('cancel') else 0,
                                     dbus.Dictionary({'uris': dbus.Array(uris, signature='s')}, signature='sv'))
                    return False
                GLib.timeout_add(10, respond)
                return dbus.ObjectPath(path)
            except Exception as error:
                failures.append(repr(error))
                raise

        @dbus.service.method('org.freedesktop.portal.FileChooser', in_signature='ssa{sv}', out_signature='o', sender_keyword='sender')
        def OpenFile(self, parent, title, options, sender=None):
            return self.choose('OpenFile', parent, title, options, sender)

        @dbus.service.method('org.freedesktop.portal.FileChooser', in_signature='ssa{sv}', out_signature='o', sender_keyword='sender')
        def SaveFile(self, parent, title, options, sender=None):
            return self.choose('SaveFile', parent, title, options, sender)

    portal = Portal(bus, '/org/freedesktop/portal/desktop')
    log = (out / 'application.log').open('w')
    process = subprocess.Popen(['cargo', 'test', '-p', 'stack-desktop', '--locked', '--offline',
                                'application_pdf_import_reorder_subset_and_output_use_saved_snapshot',
                                '--', '--include-ignored', '--nocapture'], cwd=desktop, stdout=log, stderr=log)
    loop = GLib.MainLoop()
    def poll():
        if process.poll() is None:
            return True
        loop.quit()
        return False
    GLib.timeout_add(100, poll)
    try:
        loop.run()
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
        log.close()
        report = {'headless': True, 'backend': 'private synthetic D-Bus FileChooser',
                  'requests': requests, 'failures': failures, 'application_exit': process.returncode,
                  'status': 'passed' if process.returncode == 0 and len(requests) == 6 and not failures else 'failed'}
        (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    assert report['status'] == 'passed', f'See {out}/application.log and report.json'


if __name__ == '__main__':
    main()
