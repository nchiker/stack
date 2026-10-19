#!/usr/bin/env python3
"""Author readable PDF samples and an isolated native UI review library. No private input."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


SAMPLES = [
    ('water-bill', 'Water bill — October', 'RIVERBEND WATER', 'MONTHLY WATER STATEMENT', '$48.72', ['Bills', 'House']),
    ('hardware-receipt', 'Hardware store receipt', 'OAK & IRON HARDWARE', 'PURCHASE RECEIPT', '$36.24', ['Receipts', 'House']),
    ('health-visit', 'Annual health visit', 'CEDAR FAMILY CARE', 'VISIT SUMMARY', '$25.00', ['Medical']),
    ('home-insurance', 'Home insurance renewal', 'HARBOR INSURANCE', 'POLICY RENEWAL', '$842.00', ['Bills', 'House']),
    ('bank-statement', 'September bank statement', 'NORTHRIDGE BANK', 'MONTHLY STATEMENT', '$1,425.60', ['Banking']),
    ('train-reservation', 'Train reservation', 'COASTLINE RAIL', 'RESERVATION CONFIRMATION', '$64.00', ['Receipts']),
]


def pdf_sample(sample):
    name, _, company, subtitle, amount, _ = sample
    commands = []
    def text(x, y, value, size=10, bold=False):
        value = value.replace('\\', '\\\\').replace('(', '\\(').replace(')', '\\)')
        commands.append(f'BT /{"B" if bold else "R"} {size} Tf 0.16 0.22 0.26 rg {x} {595-y} Td ({value}) Tj ET')
    def rule(y):
        commands.append(f'0.8 0.82 0.83 RG 0.6 w 30 {595-y} m 390 {595-y} l S')
    text(30, 48, company, 21, True)
    text(30, 73, subtitle, 9, True)
    text(30, 108, 'Statement date', 9)
    text(30, 125, 'October 16, 2026')
    text(270, 108, 'Account number', 9)
    text(270, 125, '1048 (sample)')
    rule(144)
    text(30, 169, 'SERVICE ADDRESS', 8, True)
    text(30, 186, '124 Maple Lane')
    text(30, 202, 'Riverbend, NC 28000')
    commands.append('0.92 0.95 0.96 rg 30 282 360 92 re f')
    text(44, 244, 'Total amount due' if name == 'water-bill' else 'Amount / statement total', 10)
    text(44, 278, amount, 28, True)
    text(44, 299, 'Payment due November 2, 2026' if name == 'water-bill' else 'Keep this document for your records', 9)
    text(30, 341, 'CURRENT CHARGES' if name == 'water-bill' else 'DOCUMENT DETAILS', 9, True)
    rows = {
        'water-bill': [('Water service - 2,400 gallons', '$31.20'), ('Base service charge', '$12.00'), ('Stormwater fee', '$5.52')],
        'hardware-receipt': [('Garden gloves', '$12.99'), ('LED bulb pack', '$20.98'), ('Sales tax', '$2.27')],
        'health-visit': [('Annual wellness visit', 'Completed'), ('Routine laboratory work', 'Requested'), ('Copayment', '$25.00')],
        'home-insurance': [('Dwelling coverage', 'Annual'), ('Personal property coverage', 'Included'), ('Policy renews', 'Nov 15, 2026')],
        'bank-statement': [('Opening balance', '$1,210.20'), ('Deposits and credits', '$2,400.00'), ('Payments and debits', '$2,184.60')],
        'train-reservation': [('Passengers', '2 adults'), ('Departure', 'Nov 8, 9:30 AM'), ('Route', 'Riverbend - Oak City')],
    }[name]
    for i, (label, value) in enumerate(rows):
        y = 367 + i * 27
        text(30, y, label, 9)
        text(285, y, value, 9)
        rule(y + 10)
    text(30, 459, 'Total current charges' if name == 'water-bill' else 'Total / balance', 10, True)
    text(285, 459, amount, 10, True)
    text(30, 495, 'Service period: September 15 - October 14, 2026', 8)
    rule(514)
    text(30, 534, 'Please include your account number with payment.', 8)
    text(30, 549, 'Thank you for helping conserve water.' if name == 'water-bill' else 'Thank you. Retain this statement for reference.', 8)
    text(30, 577, 'Authored synthetic example for Stack UI review - no personal data', 7)
    content = '\n'.join(commands).encode('ascii')
    streams = [content]
    if name == 'water-bill':
        streams.append(b'BT /B 22 Tf 30 540 Td (Service and usage details) Tj ET\nBT /R 12 Tf 30 510 Td (Meter reading: 2,400 gallons. No leaks detected.) Tj ET')
    objects = [b'', b'', b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>', b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>']
    pages = []
    for stream in streams:
        page_id = len(objects) + 1
        pages.append(page_id)
        objects.append(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 420 595] /Resources << /Font << /R 3 0 R /B 4 0 R >> >> /Contents {page_id+1} 0 R >>'.encode())
        objects.append(f'<< /Length {len(stream)} >>\nstream\n'.encode() + stream + b'\nendstream')
    objects[0] = b'<< /Type /Catalog /Pages 2 0 R >>'
    objects[1] = f'<< /Type /Pages /Kids [{" ".join(f"{i} 0 R" for i in pages)}] /Count {len(pages)} >>'.encode()
    data = bytearray(b'%PDF-1.4\n% Stack authored UI fixture v1\n')
    offsets = [0]
    for i, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data.extend(f'{i} 0 obj\n'.encode() + obj + b'\nendobj\n')
    start = len(data)
    data.extend(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode())
    data.extend(''.join(f'{offset:010} 00000 n \n' for offset in offsets[1:]).encode())
    data.extend(f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{start}\n%%EOF\n'.encode())
    return bytes(data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.absolute()
    if not out.is_relative_to('/tmp'):
        parser.error('Use a fresh /tmp output directory')
    os.umask(0o077)
    out.mkdir(mode=0o700)
    (out / 'sources').mkdir()
    desktop = Path(__file__).resolve().parents[1]
    cli = desktop / 'target/debug/stack-cli'
    env = dict(os.environ, STACK_PDFIUM_LIBRARY=str(desktop / 'runtime/pdfium/lib/libpdfium.so'))
    for kind in ('CONFIG', 'CACHE', 'DATA', 'STATE'):
        folder = out / kind.lower()
        folder.mkdir()
        env[f'XDG_{kind}_HOME'] = str(folder)
    # Copy only theme configuration; never copy another application's state.
    config = Path(os.environ.get('XDG_CONFIG_HOME', str(Path.home() / '.config')))
    for source in (config / 'cosmic').glob('com.system76.CosmicTheme*'):
        if source.is_dir():
            shutil.copytree(source, out / 'config/cosmic' / source.name)
    def run(*args):
        return json.loads(subprocess.check_output([str(cli), 'library', str(out / 'library'), *map(str, args)], env=env))
    run('status')
    stacks = {s['name']: s['id'] for s in run('manifest')['stacks']}
    hashes = {}
    for sample in reversed(SAMPLES):
        filename = sample[0] + '.pdf'
        pdf = pdf_sample(sample)
        (out / 'sources' / filename).write_bytes(pdf)
        hashes[filename] = hashlib.sha256(pdf).hexdigest()
        run('import', out / 'sources' / filename, sample[1])
        token = next(d for d in run('manifest')['documents'] if d['title'] == sample[1])
        run('file', token['id'], *[stacks[name] for name in sample[5]])
        if sample[0] == 'water-bill':
            run('notes', token['id'], 'Monthly water bill for the house.\nCompare the usage with last month.')
            for key, label, value, qualifier, kind in [('amount', 'Amount', '48.72', 'USD', 'amount'), ('due', 'Due date', '2026-11-02', 'day', 'date')]:
                detail = dict(document_id=token['id'], field_id='ui-review-' + key, kind=kind, label=label, value=value, qualifier=qualifier, origin='manual', state='saved', evidence='{}', revision=1)
                path = out / f'{key}.json'
                path.write_text(json.dumps(detail))
                run('detail', token['id'], path)
    (out / 'expected.json').write_text(json.dumps(run('manifest')))
    (out / 'fixtures.json').write_text(json.dumps(hashes, indent=2))
    print(json.dumps({'root': str(out), 'source_sha256': hashes}, indent=2))


if __name__ == '__main__':
    main()
