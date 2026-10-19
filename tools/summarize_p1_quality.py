#!/usr/bin/env python3
"""Summarize authored frozen source/OCR measurements; no human acceptance inference."""
import argparse,json,pathlib,statistics
p=argparse.ArgumentParser();p.add_argument('log',type=pathlib.Path);p.add_argument('output',type=pathlib.Path);a=p.parse_args()
line=next(x for x in a.log.read_text().splitlines() if 'P1_CORPUS_JSON ' in x);report=json.loads(line.split('P1_CORPUS_JSON ',1)[1])
summary={}
for split in ('tuning','evaluation'):
    cases=[x for x in report['cases'] if x['split']==split];summary[split]={'documents':len(cases)}
    for mode in ('source','rendered_ocr'):
        result={}
        for kind in ('all','date','amount','organization'):
            tp=fp=fn=abstained=0
            for row in cases:
                expected={k:v for k,v in row['expected'].items() if kind=='all' or k.startswith(kind+':')}
                actual={k:v for k,v in row[mode].items() if kind=='all' or k.startswith(kind+':')}
                tp+=sum(expected.get(k)==v for k,v in actual.items());fp+=sum(expected.get(k)!=v for k,v in actual.items());fn+=sum(actual.get(k)!=v for k,v in expected.items());abstained+=not actual
            result[kind]={'true':tp,'false':fp,'missed':fn,'precision':tp/(tp+fp) if tp+fp else None,'recall':tp/(tp+fn) if tp+fn else None,'documents_with_no_proposal':abstained}
        summary[split][mode]=result
photos=report['photo_detector'];tp=sum(x['candidate'] and x['expected_document'] for x in photos);fp=sum(x['candidate'] and not x['expected_document'] for x in photos);fn=sum(not x['candidate'] and x['expected_document'] for x in photos)
summary['photo_detector']={'rendered_scenes':len(photos),'positive':sum(x['expected_document'] for x in photos),'negative':sum(not x['expected_document'] for x in photos),'true':tp,'false_imports':fp,'misses':fn,'precision':tp/(tp+fp) if tp+fp else None,'recall':tp/(tp+fn) if tp+fn else None,'scope':'Agent-authored/labeled rendered scenes, not real-photo camera-roll acceptance'}
summary['limits']='Small agent-authored and agent-labeled frozen set, not blind independent evidence. Acceptance targets unagreed; human reviews pending.'
report['summary']=summary;a.output.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(summary,indent=2))
