#!/usr/bin/env python3
"""Force-stop disposable synthetic restores at durable boundaries; no personal data access."""
import argparse, pathlib, subprocess, uuid
parser=argparse.ArgumentParser()
parser.add_argument('--adb',required=True);parser.add_argument('--serial',required=True)
parser.add_argument('--output',type=pathlib.Path,required=True)
args=parser.parse_args();adb=[args.adb,'-s',args.serial];runner='org.stackdocs.app.checks.test/org.stackdocs.app.ScannerChecks'
logs=[]
for boundary in ('staged','journal','file','transaction','committed'):
    key=uuid.uuid4().hex
    base=adb+['shell','am','instrument','-w','-r','-e','ocrRestartKey',key,'-e','archiveBoundary',boundary,'-e','archiveRestart']
    process=subprocess.Popen(base+['prepare',runner],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    killed=False
    for line in process.stdout:
        logs.append(line)
        if 'ARCHIVE_RESTART_READY' in line:
            subprocess.run(adb+['shell','am','force-stop','org.stackdocs.app.checks'],check=True);killed=True
    process.wait(timeout=40)
    if not killed:raise SystemExit('No durable boundary: '+''.join(logs))
    result=subprocess.run(base+['resume',runner],capture_output=True,text=True,timeout=60)
    logs.append(result.stdout+result.stderr)
    args.output.write_text(''.join(logs))
    if 'INSTRUMENTATION_CODE: -1' not in result.stdout:raise SystemExit('Restore recovery failed: '+result.stdout)
    print(boundary+': PASS',flush=True)
