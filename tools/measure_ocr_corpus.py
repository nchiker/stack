#!/usr/bin/env python3
"""Run the authored device corpus; record only synthetic results and process memory totals."""
import argparse, json, pathlib, re, subprocess, threading, time, unicodedata

parser=argparse.ArgumentParser()
parser.add_argument('--adb',required=True)
parser.add_argument('--serial',required=True)
parser.add_argument('--output',type=pathlib.Path,required=True)
parser.add_argument('--package',default='org.stackdocs.app',choices=['org.stackdocs.app','org.stackdocs.app.checks'])
args=parser.parse_args()
adb=[args.adb,'-s',args.serial]
stop=threading.Event(); memory=[]
def sample():
    while not stop.is_set():
        result=subprocess.run(adb+['shell','dumpsys','meminfo',args.package],capture_output=True,text=True)
        match=re.search(r'TOTAL PSS:\s*(\d+)',result.stdout)
        if match: memory.append({'time_monotonic':time.monotonic(),'pss_kib':int(match.group(1))})
        stop.wait(.25)
thread=threading.Thread(target=sample);thread.start()
try:
    run=subprocess.run(adb+['shell','am','instrument','-w','-r','-e','ocrCorpus','true',args.package+'.test/org.stackdocs.app.ScannerChecks'],capture_output=True,text=True)
finally: stop.set();thread.join()
args.output.parent.mkdir(parents=True,exist_ok=True)
args.output.with_suffix('.log').write_text(run.stdout+run.stderr)
rows=[]
for line in run.stdout.splitlines():
    if 'OCR_CORPUS {' in line: rows.append(json.loads(line.split('OCR_CORPUS ',1)[1]))
def normalize(text): return ' '.join(unicodedata.normalize('NFC',text.replace('\r\n','\n').replace('\r','\n')).split())
def distance(left,right):
    prior=list(range(len(right)+1))
    for i,a in enumerate(left,1):
        current=[i]
        for j,b in enumerate(right,1): current.append(min(current[-1]+1,prior[j]+1,prior[j-1]+(a!=b)))
        prior=current
    return prior[-1]
def scores(selected):
    chars=words=char_errors=word_errors=0
    for row in selected:
        truth=normalize(row['truth']);actual=normalize(row['actual'])
        chars+=len(truth);words+=len(truth.split())
        char_errors+=distance(truth,actual);word_errors+=distance(truth.split(),actual.split())
    return {'characters':chars,'character_errors':char_errors,'cer':char_errors/chars if chars else None,'words':words,'word_errors':word_errors,'wer':word_errors/words if words else None}
def percentile(values,p):
    import math
    return sorted(values)[max(0,math.ceil(len(values)*p)-1)] if values else None
summaries={}
for edge in (1600,2400):
    selected=[row for row in rows if row['edge']==edge]
    summaries[str(edge)]={'all':scores(selected),'clean':scores([row for row in selected if row['group']=='clean']),
        'difficult':scores([row for row in selected if row['group'] in ('receipt','form')]),
        'p50_ms':percentile([r['elapsed_ms'] for r in selected],.50),'p95_ms':percentile([r['elapsed_ms'] for r in selected],.95)}
    for row in selected: row['metrics']=scores([row])
report={'device':subprocess.check_output(adb+['shell','getprop','ro.product.model'],text=True).strip(),
        'api':subprocess.check_output(adb+['shell','getprop','ro.build.version.sdk'],text=True).strip(),
        'normalization':'Unicode NFC; normalized line endings; collapsed whitespace; case/digits/punctuation preserved; Levenshtein edits weighted by ground-truth length',
        'all_synthetic':True,'samples':rows,'summary':summaries,'pss_samples':memory,
        'sampled_peak_pss_kib':max([m['pss_kib'] for m in memory],default=None),
        'instrumentation_passed':'INSTRUMENTATION_CODE: -1' in run.stdout}
args.output.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({key:value for key,value in report.items() if key not in ('samples','pss_samples')},indent=2))
if not report['instrumentation_passed'] or len(rows)!=72: raise SystemExit('Incomplete corpus; see log')
