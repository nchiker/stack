#!/usr/bin/env python3
"""SIGKILL synthetic sync import/publication at actual durability boundaries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('cli',type=Path)
    parser.add_argument('fixtures',type=Path)
    parser.add_argument('output',type=Path)
    args=parser.parse_args()
    cli=args.cli.resolve();fixtures=args.fixtures.resolve();output=args.output.resolve()
    assert str(output).startswith('/tmp/')
    assert json.loads((fixtures/'synthetic-fixture.json').read_text())['fixture']=='stack-m1'
    output.mkdir(mode=0o700)
    def command(*parts):
        return json.loads(subprocess.check_output([str(cli),*map(str,parts)],text=True))
    def manifest(root):
        graph=command('library',root,'manifest');graph['created_at']=0;return graph
    a=output/'source';b=output/'destination'
    command('archive',a,'restore',fixtures/'format-3.stackbackup')
    peer=command('sync',b,'status')['replica']
    initial=command('sync',a,'start',peer)
    baseline=output/'baseline.stackexchange';command('sync',a,'export',peer,baseline)
    command('sync',b,'adopt',baseline,initial['library'],*initial['members'])
    document=manifest(a)['documents'][0]['id']
    before=manifest(b)
    command('library',a,'notes',document,'Authored offline decision 00024.500000 GBP 🦀')
    incoming=output/'incoming.stackexchange';command('sync',a,'export',peer,incoming)
    after=manifest(a)
    cases=[]
    for checkpoint in ['sync_originals','sync_transaction','sync_committed','sync_before_receipt','sync_receipt','sync_bundle_written','sync_bundle_published']:
        root=output/checkpoint
        # File copies are isolated test replicas only; no copied database is ever
        # a protocol input. The fixture has no live process/WAL while copied.
        import shutil
        shutil.copytree(b,root)
        (root/'.stack-m1-disposable').write_text('stack-m1-synthetic-only\n')
        ready=output/(checkpoint+'.ready')
        publication=checkpoint.startswith('sync_bundle_')
        kind='sync-publish' if publication else 'sync-import'
        with (output/(checkpoint+'.log')).open('w') as log:
            child=subprocess.Popen([str(cli),'m1-interrupt',str(root),kind,str(incoming),checkpoint,str(ready)],stdout=log,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+40
            while not ready.exists() and child.poll() is None and time.monotonic()<deadline:time.sleep(.02)
            if not ready.exists():
                if child.poll() is None:child.kill()
                child.wait();raise AssertionError(f'{checkpoint} not reached; inspect {log.name}')
            child.kill();assert child.wait(timeout=10)==-9
        committed=checkpoint in ['sync_committed','sync_before_receipt','sync_receipt']
        assert manifest(root)==(after if committed else before),checkpoint
        receipt_before=command('sync',root,'status')['acknowledged_by']
        if publication:
            published=root/'published.stackexchange'
            assert published.exists()==(checkpoint=='sync_bundle_published')
            # Hidden partials never count as a completed inbox file.
            assert all(p.name.endswith('.part') for p in root.glob('.stack-exchange-*'))
        command('sync',root,'import',incoming)
        token_graph=manifest(root)
        command('sync',root,'import',incoming)
        assert manifest(root)==token_graph==after
        assert not list((root/'operations').iterdir())
        for file in after['files']:
            assert hashlib.sha256((root/file['path']).read_bytes()).hexdigest()==file['sha256']
        cases.append(dict(checkpoint=checkpoint,termination='SIGKILL',pre_replay_committed=committed,receipt_before=receipt_before,retry_and_replay='passed',originals='unchanged'))
        print(checkpoint+': passed',flush=True)
    report=dict(scope='Actual process termination, not power-loss simulation',cases=cases,cli_sha256=hashlib.sha256(cli.read_bytes()).hexdigest(),bundle_sha256=hashlib.sha256(incoming.read_bytes()).hexdigest())
    (output/'report.json').write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':main()
