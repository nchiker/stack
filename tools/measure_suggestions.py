#!/usr/bin/env python3
"""Run only isolated authored fixtures, sample process PSS, score multi-label proposals.
No production document text or metadata is read. APKs must be installed in place first.
"""
import argparse, hashlib, json, math, pathlib, re, subprocess, threading, time
parser=argparse.ArgumentParser()
parser.add_argument('--adb',required=True); parser.add_argument('--serial',required=True)
parser.add_argument('--output',required=True,type=pathlib.Path)
parser.add_argument('--corpus',default='suggestions-corpus.json',choices=['suggestions-corpus.json','naming-followup-corpus.json','p0-final-corpus.json'])
parser.add_argument('--package',default='org.stackdocs.app',choices=['org.stackdocs.app','org.stackdocs.app.checks'])
args=parser.parse_args(); adb=[args.adb,'-s',args.serial]
process=subprocess.Popen(adb+['shell','am','instrument','-w','-r','-e','suggestionCorpus','true','-e','suggestionCorpusAsset',args.corpus,args.package+'.test/org.stackdocs.app.ScannerChecks'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True)
samples=[]; stop=threading.Event()
def sample():
    while not stop.wait(.25):
        result=subprocess.run(adb+['shell','dumpsys','meminfo',args.package],capture_output=True,text=True)
        found=re.search(r'TOTAL PSS:\s+(\d+)',result.stdout)
        if found:samples.append(int(found.group(1)))
thread=threading.Thread(target=sample);thread.start()
report=None;lines=[]
try:
    for line in process.stdout:
        lines.append(line)
        if 'SUGGESTION_CORPUS_JSON ' in line:report=json.loads(line.split('SUGGESTION_CORPUS_JSON ',1)[1])
    process.wait(timeout=30)
finally:
    stop.set();thread.join(timeout=15)
if report is None or 'INSTRUMENTATION_CODE: -1' not in ''.join(lines):raise SystemExit('Isolated corpus failed: '+''.join(lines))
def percent(n,d):return round(100*n/d,3) if d else None
def percentile(values,q):return sorted(values)[max(0,math.ceil(q*len(values))-1)]
summary={}
for split in ['all']+sorted({r['split'] for r in report['documents']}):
    selected=[r for r in report['documents'] if split=='all' or r['split']==split]
    summary[split]={}
    for mode in ('rule_text','ocr'):
        tp=fp=fn=covered=eligible_covered=negative_fp=0;per={c:dict(tp=0,fp=0,fn=0) for c in ('bills','banking','ids','house','medical','receipts')}
        eligible=sum(bool(r['labels']) for r in selected);negatives=len(selected)-eligible
        for row in selected:
            predicted=set(row[mode]['stacks']); expected=set(row['labels'])
            tp+=len(predicted&expected);fp+=len(predicted-expected);fn+=len(expected-predicted)
            covered+=bool(predicted);eligible_covered+=bool(predicted) and bool(expected);negative_fp+=bool(predicted) and not expected
            for category,c in per.items():
                c['tp']+=category in predicted&expected;c['fp']+=category in predicted-expected;c['fn']+=category in expected-predicted
        summary[split][mode]=dict(documents=len(selected),tp=tp,fp=fp,fn=fn,precision_pct=percent(tp,tp+fp),recall_pct=percent(tp,tp+fn),coverage_pct=percent(covered,len(selected)),abstention_pct=percent(len(selected)-covered,len(selected)),eligible_coverage_pct=percent(eligible_covered,eligible),negative_false_positive_documents=negative_fp,negative_documents=negatives,per_category=per)
report['metrics']=summary
expected=[row for row in report['documents'] if 'expected_name' in row]
if expected:
    report['structural_name_expectations']={mode:{'cases':len(expected),'matches':sum(row[mode]['title']==row['expected_name'] for row in expected),'mismatches':[row['id'] for row in expected if row[mode]['title']!=row['expected_name']]} for mode in ('rule_text','ocr')}
report['timings_ms']={key:dict(p50=percentile([r[key] for r in report['documents']],.5),p95=percentile([r[key] for r in report['documents']],.95),max=max(r[key] for r in report['documents'])) for key in ('rule_ms','proposal_snapshot_analysis_commit_ms','ocr_ms')}
report['memory']={'external_total_pss_samples':len(samples),'external_max_total_pss_kib':max(samples) if samples else None,'interval_seconds':.25,'caveat':'Sampled total process including instrumentation, fixture rendering and real OCR; command overhead adds to interval. Not an instantaneous maximum or isolated rules allocation.'}
corpus=pathlib.Path(__file__).resolve().parents[1]/'app/src/androidTest/assets'/args.corpus
report['corpus_sha256']=hashlib.sha256(corpus.read_bytes()).hexdigest()
args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps({'metrics':summary,'timings_ms':report['timings_ms'],'memory':report['memory']},indent=2))
