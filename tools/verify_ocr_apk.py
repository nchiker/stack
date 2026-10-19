#!/usr/bin/env python3
"""Static audit of the actual APK. Requires readelf; never reads a device library."""
import argparse, hashlib, json, pathlib, struct, subprocess, tempfile, zipfile

parser = argparse.ArgumentParser()
parser.add_argument('apk', type=pathlib.Path)
args = parser.parse_args()
report = {'apk_bytes': args.apk.stat().st_size, 'apk_sha256': hashlib.sha256(args.apk.read_bytes()).hexdigest(), 'native': []}
with zipfile.ZipFile(args.apk) as apk, tempfile.TemporaryDirectory() as temporary:
    manifest = json.loads(apk.read('assets/ocr/manifest.json'))
    model = apk.read('assets/ocr/eng.traineddata')
    assert hashlib.sha256(model).hexdigest() == manifest['model_sha256']
    assert len(model) == manifest['model_bytes']
    report['model'] = manifest
    for name in sorted(apk.namelist()):
        if not (name.startswith('lib/') and name.endswith('.so')):
            continue
        data = apk.read(name)
        file = pathlib.Path(temporary, pathlib.Path(name).name)
        file.write_bytes(data)
        headers = subprocess.check_output(['readelf', '-lW', str(file)], text=True)
        alignments = [int(line.split()[-1], 16) for line in headers.splitlines() if line.strip().startswith('LOAD ')]
        abi = name.split('/')[1]
        assert alignments and min(alignments) >= (16384 if abi in ('arm64-v8a', 'x86_64') else 4096)
        dependencies = subprocess.check_output(['readelf', '-dW', str(file)], text=True)
        needed = [line.split('[')[-1].rstrip(']') for line in dependencies.splitlines() if '(NEEDED)' in line]
        entry = apk.getinfo(name)
        with args.apk.open('rb') as source:
            source.seek(entry.header_offset)
            header = source.read(30)
            namesize, extrasize = struct.unpack_from('<HH', header, 26)
        offset = entry.header_offset + 30 + namesize + extrasize
        assert entry.compress_type == zipfile.ZIP_STORED and offset % 16384 == 0
        report['native'].append({'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest(), 'load_alignments': alignments, 'zip_offset': offset, 'needed': needed})
    assert {n['path'].split('/')[1] for n in report['native']} == {'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'}
    assert len(report['native']) == 16
print(json.dumps(report, indent=2))
