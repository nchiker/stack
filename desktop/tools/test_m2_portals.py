#!/usr/bin/env python3
"""Real native GTK portal controls on an isolated bus; app opening actions injected."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import struct
import subprocess
import sys
import time


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--java-classes', required=True, type=Path)
    parser.add_argument('--worker', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    out = args.output.absolute()
    if not out.is_relative_to('/tmp'):
        parser.error('Use a fresh disposable /tmp output')
    os.umask(0o077)
    desktop = Path(__file__).resolve().parents[1]
    if not args.worker:
        out.mkdir(mode=0o700)
        env = dict(os.environ)
        for name in ('DATA', 'CONFIG', 'CACHE', 'STATE', 'RUNTIME'):
            folder = out / name.lower()
            folder.mkdir(mode=0o700)
            env[f'XDG_{name}_HOME' if name != 'RUNTIME' else 'XDG_RUNTIME_DIR'] = str(folder)
        portal = out / 'config/xdg-desktop-portal'
        portal.mkdir()
        (portal / 'portals.conf').write_text('[preferred]\ndefault=gtk\norg.freedesktop.impl.portal.FileChooser=gtk\norg.freedesktop.impl.portal.Secret=none\n')
        # Keep all services/settings local to this owned test bus and runtime.
        parent_socket = Path(os.environ['WAYLAND_DISPLAY'])
        if not parent_socket.is_absolute():
            parent_socket = Path(os.environ['XDG_RUNTIME_DIR']) / parent_socket
        env.update(WAYLAND_DISPLAY=str(parent_socket), XDG_CURRENT_DESKTOP='GNOME',
                   GIO_USE_VFS='local', GIO_USE_VOLUME_MONITOR='unix', STACK_M2_PRIVATE_PORTAL='1')
        for name in ('SSH_AUTH_SOCK', 'GNOME_KEYRING_CONTROL', 'AT_SPI_BUS_ADDRESS', 'WAYLAND_DEBUG'):
            env.pop(name, None)
        with (out / 'services.log').open('w') as log:
            result = subprocess.run(['dbus-run-session', '--', sys.executable, str(Path(__file__).resolve()),
                                     '--worker', '--output', str(out), '--java-classes', str(args.java_classes.absolute())],
                                    env=env, stdout=log, stderr=subprocess.STDOUT, timeout=180)
        if result.returncode:
            raise RuntimeError(f'Native portal test failed; see {out / "services.log"}')
        print((out / 'report.json').read_text())
        return

    assert os.environ.get('STACK_M2_PRIVATE_PORTAL') == '1'
    assert os.environ['DBUS_SESSION_BUS_ADDRESS'] != 'unix:path=/run/user/1000/bus'
    assert os.environ['XDG_RUNTIME_DIR'] == str(out / 'runtime')
    import dbus
    bus = dbus.SessionBus()
    status = bus.get_object('org.a11y.Bus', '/org/a11y/bus')
    status.Set('org.a11y.Status', 'ScreenReaderEnabled', dbus.Boolean(True),
               dbus_interface='org.freedesktop.DBus.Properties')
    os.environ['AT_SPI_BUS_ADDRESS'] = str(status.GetAddress(dbus_interface='org.a11y.Bus'))
    import gi
    gi.require_version('Atspi', '2.0')
    from gi.repository import Atspi, GLib
    Atspi.init()
    registry = Atspi.get_desktop(0)
    env = dict(os.environ, STACK_PDFIUM_LIBRARY=str(desktop / 'runtime/pdfium/lib/libpdfium.so'))
    cli, binary = [desktop / f'target/debug/{name}' for name in ('stack-cli', 'stack-desktop')]

    def run(command, label):
        with (out / f'{label}.log').open('w') as log:
            subprocess.run(list(map(str, command)), env=env, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=45)

    for label, command in [('fixtures', [cli, 'fixtures', out / 'render']),
                           ('archives', [cli, 'm1-fixtures', out / 'render', out / 'archive']),
                           ('seed', [cli, 'archive', out / 'library', 'restore', out / 'archive/format-3.stackbackup'])]:
        run(command, label)
    originals = {str(p.relative_to(out / 'library')): digest(p) for p in (out / 'library/originals').glob('*/*') if p.name != '.owner'}
    app_log = (out / 'app.log').open('w')
    app = subprocess.Popen([str(binary), '--library', str(out / 'library'), '--browser-smoke-fixtures', str(out / 'render'),
                            '--browser-smoke-case', 'portal', '--smoke-output', str(out / 'portal')],
                           env=env, stdout=app_log, stderr=app_log)
    report = {'synthetic_only': True, 'private_bus_and_runtime': True, 'backend': 'installed GTK XDG portal',
              'interaction': 'app opening actions/restore decisions injected; native chooser controls via AT-SPI',
              'physical_keyboard_or_pointer': False, 'binary_sha256': digest(binary), 'status': 'running'}

    def wait(check, label, seconds=30):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            context = GLib.MainContext.default()
            while context.pending():
                context.iteration(False)
            try:
                result = check()
            except GLib.Error:
                # Native dialog objects can disappear between enumeration and
                # a property read after Open/Save/Cancel. Re-enumerate them.
                result = None
            if result:
                return result
            if app.poll() is not None:
                raise RuntimeError(f'Application exited {app.returncode} while waiting for {label}')
            time.sleep(.1)
        raise TimeoutError(label)

    def walk(node, depth=0):
        if depth > 16:
            return
        node.clear_cache()
        yield node
        for i in range(min(node.get_child_count(), 200)):
            yield from walk(node.get_child_at_index(i), depth + 1)

    def dialog(label, previous=None):
        for i in range(registry.get_child_count()):
            client = registry.get_child_at_index(i)
            for j in range(client.get_child_count()):
                window = client.get_child_at_index(j)
                name = window.get_name()
                if name == nonce + ' · ' + label and window != previous:
                    return window
        return None

    def named(window, name, role=None):
        return next((n for n in walk(window) if n.get_name() == name and (role is None or n.get_role_name() == role)), None)

    def click(window, label):
        node = named(window, label, 'button')
        assert node is not None and node.get_action_iface().do_action(0), label

    def count():
        with sqlite3.connect(f'file:{out}/library/library.sqlite3?mode=ro', uri=True) as db:
            return db.execute('SELECT count(*) FROM documents WHERE active=1').fetchone()[0]

    def set_location(window, text):
        chooser = named(window, 'File Chooser Widget')
        assert chooser.get_action_iface().do_action(0)  # Native show_location action.
        def entry():
            candidates = [n for n in walk(window) if 'EditableText' in n.get_interfaces()]
            return next((n for n in candidates if n.get_name().rstrip(':') == 'Location'), None) or next(
                (n for n in candidates if n.get_state_set().contains(Atspi.StateType.SHOWING)), None)
        try:
            field = wait(entry, 'native location entry', seconds=10)
        except TimeoutError:
            (out / 'editable-diagnostic.json').write_text(json.dumps([
                {'name': n.get_name(), 'role': n.get_role_name(),
                 'states': [str(s) for s in n.get_state_set().get_states()]}
                for n in walk(window) if 'EditableText' in n.get_interfaces()], indent=2))
            raise
        assert Atspi.Component.grab_focus(field), 'native chooser entry focus'
        assert Atspi.EditableText.set_text_contents(field, text)

    def open_archive(previous=None):
        window = wait(lambda: dialog('Review a Stack archive', previous), 'archive chooser')
        set_location(window, str(out / 'portal/Stack-library.stackbackup'))
        click(window, 'Open')
        return window

    try:
        nonce = wait(lambda: (re.search(r'Synthetic test window: (Stack synthetic M2 [0-9a-f-]{36})',
                                       (out / 'app.log').read_text()) or [None, None])[1], 'synthetic title')
        first = wait(lambda: dialog('Import documents'), 'initial source chooser')
        assert count() == 5
        click(first, 'Cancel')
        second = wait(lambda: dialog('Import documents', first), 'second source chooser')
        assert count() == 5
        table = wait(lambda: named(second, 'Files', 'table'), 'source file list')
        table_api = table.get_table_iface()
        wait(lambda: table_api.get_n_rows() == 4, 'four filtered authored sources')
        expected_names = {'two-pages.pdf', 'oriented.jpg', 'corners.png', 'corners.webp'}
        def listed_names():
            names = set()
            for row in range(4):
                for column in range(table_api.get_n_columns()):
                    for node in walk(table_api.get_accessible_at(row, column)):
                        name = node.get_name()
                        if name in expected_names:
                            names.add(name)
                        if 'Text' in node.get_interfaces():
                            text = Atspi.Text.get_text(node, 0, -1)
                            if text in expected_names:
                                names.add(text)
            return names if names == expected_names else None
        wait(listed_names, 'authored filenames in native table cells')
        # A GTK Location entry is a single path, not a shell command line.
        # Import each authored source through its own native request rather than
        # supplying quotes that can become literal filename characters.
        current = second
        previous = first
        for name in sorted(expected_names):
            current = wait(lambda: dialog('Import documents', previous), 'source chooser')
            set_location(current, str(out / 'render' / name))
            click(current, 'Open')
            previous = current
        archive_save = wait(lambda: dialog('Export full portable library archive'), 'archive save chooser')
        assert count() == 9
        click(archive_save, 'Save')
        first_archive = open_archive()
        second_archive = wait(lambda: dialog('Review a Stack archive', first_archive), 'second archive chooser after review cancellation')
        assert count() == 9
        open_archive(first_archive)
        pdf_save = wait(lambda: dialog('Export saved document as PDF'), 'PDF save chooser')
        assert count() == 18
        click(pdf_save, 'Save')
        assert app.wait(timeout=30) == 0
        for path, value in originals.items():
            assert digest(out / 'library' / path) == value
        pdfs = list((out / 'portal').glob('*.pdf'))
        assert len(pdfs) == 1 and pdfs[0].read_bytes() == (out / 'render/two-pages.pdf').read_bytes()
        assert not list((out / 'library/operations').iterdir())
        portable = out / 'portal/Stack-library.stackbackup'
        with portable.open('rb') as source:
            assert source.read(8) == b'STACKBK\x01'
            length = struct.unpack('>i', source.read(4))[0]
            checksum, raw = source.read(32), source.read(length)
            assert hashlib.sha256(raw).digest() == checksum
            graph = json.loads(raw)
            assert len(graph['documents']) == 9 and len(graph['pages']) == 12
        run(['java', '-cp', args.java_classes, 'org.stackdocs.app.ArchiveHost', 'read', portable, out / 'java'], 'java')
        from test_m1_java_interop import canonical
        assert canonical(json.loads((out / 'java/manifest-v3.json').read_text())) == canonical(graph)
        report.update(status='passed', initial_cancel_preserves_five=True, four_types_selected_through_native_chooser=True,
                      native_archive_saved_documents=9, canceled_review_preserves_nine=True,
                      committed_documents=18, native_pdf_copy_byte_identical=True, originals_retained=True,
                      host_java_export_graph_equal=True)
    except Exception as error:
        report.update(status='failed', error=repr(error))
        raise
    finally:
        if app.poll() is None:
            app.terminate()
            app.wait(timeout=10)
        app_log.close()
        (out / 'report.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
