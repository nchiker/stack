#!/usr/bin/env python3
"""Compile unchanged production Java archive members with pinned AOSP JSON.

This host probe covers metadata and the real Java writer. It deliberately excludes
Android SQLite, PdfRenderer/BitmapFactory, restore, permission and lifecycle APIs.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/org/stackdocs/app'


def member(source, signature):
    """Extract one member verbatim, balancing braces outside comments/literals."""
    start = source.index(signature)
    masked = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*.*?\*/',
                    lambda m: ' ' * len(m[0]), source, flags=re.S)
    opening = masked.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (masked[end] == '{') - (masked[end] == '}')
        end += 1
    return source[start:end]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('aosp_json', type=Path)
    parser.add_argument('new_output', type=Path)
    args = parser.parse_args()
    args.new_output.mkdir()
    src = args.new_output / 'src'
    package = src / 'org/stackdocs/app'
    package.mkdir(parents=True)
    records = json.loads(Path(__file__).with_name('android-json-sources.json').read_text())
    for record in records:
        path = args.aosp_json / record['file']
        if hashlib.sha256(path.read_bytes()).hexdigest() != record['sha256']:
            raise SystemExit(f'AOSP source hash mismatch: {path}')
        target = src / 'org/json' / path.name
        target.parent.mkdir(exist_ok=True)
        shutil.copyfile(path, target)
    sources = {}
    for name in ['LibraryArchive', 'ArchiveJson', 'PageGeometry', 'PageState', 'DetailValue', 'SuggestionRules', 'LibraryRepository', 'SuggestionStore']:
        text = (JAVA / (name + '.java')).read_text()
        sources[name] = text
        if name in ['ArchiveJson', 'PageGeometry', 'PageState', 'DetailValue']:
            (package / (name + '.java')).write_text(text)
    header = 'package org.stackdocs.app;\nimport org.json.*;\nimport java.io.*;\nimport java.util.*;\nimport java.nio.*;\nimport java.nio.charset.*;\nimport java.security.*;\n'
    source = sources['LibraryArchive']
    constants = source[source.index('    static final byte[] MAGIC='):source.index('    private final File root, originals;')]
    signatures = ['static final class Progress', 'static final class Snapshot', 'static void write(',
                  'private static int filesIndex(', 'static void validate(', 'private static void upgradeOlder(',
                  'private static void decision(', 'private static void name(', 'private static JSONArray array(',
                  'private static long number(', 'private static String str(', 'private static void keys(',
                  'private static void sha(', 'private static void uuid(', 'private static void stackId(',
                  'private static void path(', 'private static String[] cols(', 'private static List<JSONObject> objects(',
                  'private static MessageDigest digest(', 'private static String hex(', 'private static byte[] hash(',
                  'private static void transfer(', 'private static String utf8(', 'static void remove(', 'private static IOException invalid(']
    selected = [member(source, s) for s in signatures]
    # Package visibility only for the two calls made by the host driver. Bodies stay byte-identical.
    selected = [s.replace('private static void validate(', 'static void validate(', 1)
                .replace('private static void upgradeOlder(', 'static void upgradeOlder(', 1) for s in selected]
    (package / 'LibraryArchive.java').write_text(header + 'final class LibraryArchive {\n' + constants + '\n'.join(selected) + '\n}\n')
    (package / 'LibraryRepository.java').write_text(header + 'final class LibraryRepository {\n' + member(sources['LibraryRepository'], 'static String cleanName(') + '\n}\n')
    (package / 'SuggestionRules.java').write_text(header + 'final class SuggestionRules {\n' + member(sources['SuggestionRules'], 'static final class Result') + '\n}\n')
    digest=member(sources['SuggestionStore'], 'private static final class Digest').replace('private static final class Digest','static final class Digest',1)
    (package / 'SuggestionStore.java').write_text(header + 'final class SuggestionStore {\n' + digest + '\n}\n')
    (package / 'MetadataStore.java').write_text('package org.stackdocs.app; final class MetadataStore { static final int NOTES_LIMIT=20000; }\n')
    textutils = src / 'android/text/TextUtils.java'
    textutils.parent.mkdir(parents=True)
    textutils.write_text('package android.text; public class TextUtils { public static String join(CharSequence d,Object[] a){return String.join(d,java.util.Arrays.stream(a).map(Object::toString).toArray(String[]::new));} }\n')
    shutil.copyfile(Path(__file__).parent / 'java/ArchiveHost.java', package / 'ArchiveHost.java')
    classes = args.new_output / 'classes'
    classes.mkdir()
    subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(classes), *map(str, sorted(src.rglob('*.java')))], check=True)
    report = {'scope': 'Unchanged production validator/writer/helper bodies; host envelope reader. No Android native/runtime restore claim.',
              'java': subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True).strip(),
              'production_sha256': {name + '.java': hashlib.sha256(text.encode()).hexdigest() for name, text in sources.items()},
              'extracted_members': signatures, 'aosp_json': records}
    (args.new_output / 'sources.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'java -cp {classes} org.stackdocs.app.ArchiveHost read|write ...')


if __name__ == '__main__':
    main()
