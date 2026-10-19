#!/usr/bin/env python3
"""Stop disposable Stack only at durable P1 synthetic import/delete/metadata boundaries."""
import argparse, pathlib, subprocess, uuid
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',required=True);p.add_argument('--output',type=pathlib.Path,required=True);a=p.parse_args()
adb=[a.adb,'-s',a.serial];logs=[]
for boundary in ('staged','committed','delete','metadata'):
    base=adb+['shell','am','instrument','-w','-r','-e','ocrRestartKey',uuid.uuid4().hex,'-e','p1Boundary',boundary,'-e','p1Restart']
    process=subprocess.Popen(base+['prepare','org.stackdocs.app.checks.test/org.stackdocs.app.ScannerChecks'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
    killed=False
    for line in process.stdout:
        logs.append(line)
        if 'P1_RESTART_READY' in line:
            subprocess.run(adb+['shell','am','force-stop','org.stackdocs.app.checks'],check=True);killed=True
    process.wait(timeout=40)
    if not killed:raise SystemExit('P1 boundary not reached: '+''.join(logs))
    result=subprocess.run(base+['resume','org.stackdocs.app.checks.test/org.stackdocs.app.ScannerChecks'],capture_output=True,text=True,timeout=110);logs.append(result.stdout+result.stderr);a.output.write_text(''.join(logs))
    if 'INSTRUMENTATION_CODE: -1' not in result.stdout:raise SystemExit('P1 recovery failed: '+result.stdout)
    print(boundary+': PASS',flush=True)
