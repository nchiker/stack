#!/usr/bin/env python3
"""Force-stop a held isolated suggestion claim, preserving the canonical library."""
import argparse,pathlib,subprocess,uuid
parser=argparse.ArgumentParser();parser.add_argument('--adb',required=True);parser.add_argument('--serial',required=True);parser.add_argument('--output',type=pathlib.Path,required=True)
parser.add_argument('--package',default='org.stackdocs.app',choices=['org.stackdocs.app','org.stackdocs.app.checks'])
args=parser.parse_args();adb=[args.adb,'-s',args.serial];key=uuid.uuid4().hex
base=adb+['shell','am','instrument','-w','-r','-e','ocrRestartKey',key,'-e','suggestionRestart']
runner=args.package+'.test/org.stackdocs.app.ScannerChecks'
process=subprocess.Popen(base+['prepare',runner],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
lines=[];killed=False
for line in process.stdout:
    lines.append(line)
    if 'SUGGESTION_RESTART_READY' in line:
        subprocess.run(adb+['shell','am','force-stop',args.package],check=True);killed=True
process.wait(timeout=30)
if not killed:raise SystemExit('No durable claim: '+''.join(lines))
result=subprocess.run(base+['resume',runner],capture_output=True,text=True,timeout=60)
args.output.write_text(''.join(lines)+'\nRESUMED IN NEW PROCESS\n'+result.stdout+result.stderr)
print(result.stdout)
if 'INSTRUMENTATION_CODE: -1' not in result.stdout:raise SystemExit('Suggestion recovery failed')
