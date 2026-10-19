#!/usr/bin/env python3
"""Local, one-time conversion of user-authorized Google Stack data to Stack archive 2.

No network, account APIs, APK/code reuse or library mutation. Requires an unencrypted
Android backup, Python 3.9+ and pdfinfo. Output and work directory must be outside Git.
Only SQLite and document media are extracted, under private hashed names. Logs contain
aggregate counts only. Source enum mapping must be confirmed against the original UI.
"""
import argparse,base64,collections,datetime,hashlib,io,json,os,pathlib,shutil,sqlite3,struct,subprocess,tarfile,uuid,zlib
from zoneinfo import ZoneInfo
PACKAGE='com.area120.paperwork'
NS=uuid.UUID('43623486-a2e5-4d44-b053-28eb7277d221')
LIMIT=256*1024*1024
class MigrationError(Exception):pass
class Inflate(io.RawIOBase):
    def __init__(self,source):self.source=source;self.decoder=zlib.decompressobj();self.pending=b'';self.done=False
    def readable(self):return True
    def readinto(self,dest):
        while not self.pending and not self.done:
            data=self.decoder.unconsumed_tail or self.source.read(65536)
            if data:self.pending=self.decoder.decompress(data,65536)
            else:
                self.pending=self.decoder.flush();self.done=True
                if not self.decoder.eof:raise MigrationError('Truncated Android backup.')
        n=min(len(dest),len(self.pending));dest[:n]=self.pending[:n];self.pending=self.pending[n:];return n

def stable(kind,value):return str(uuid.uuid5(NS,kind+':'+str(value)))
def compact(value):return json.dumps(value,ensure_ascii=False,separators=(',',':'),allow_nan=False)
def units(value):return len(value.encode("utf-16-le"))//2
def clean(value,limit=200):
    if not isinstance(value,str) or not value.strip() or value!=value.strip() or units(value)>limit or any(ord(c)<32 or ord(c)==127 for c in value):raise MigrationError('A source name/value exceeds the supported contract; nothing was imported.')
    return value

def unpack(source,work):
    work.mkdir(mode=0o700,parents=True,exist_ok=True);work.chmod(0o700);media={};dbs=[]
    with source.open('rb') as stream:
        if stream.readline()!=b'ANDROID BACKUP\n':raise MigrationError('Expected an Android backup, not a PDF/ZIP.')
        version=stream.readline().strip();compressed=stream.readline().strip();encryption=stream.readline().strip()
        if version not in (b'1',b'2',b'3',b'4',b'5') or compressed not in (b'0',b'1'):raise MigrationError('Unsupported Android backup header.')
        if encryption!=b'none':raise MigrationError('Decrypt the backup locally first; never send passwords in chat.')
        data=io.BufferedReader(Inflate(stream)) if compressed==b'1' else stream
        total=0
        with tarfile.open(fileobj=data,mode='r|') as archive:
            prefix='apps/'+PACKAGE+'/'
            for item in archive:
                if not item.isfile() or not item.name.startswith(prefix):continue
                if item.size>LIMIT:raise MigrationError('A source file exceeds 256 MiB.')
                total+=item.size
                if total>8*1024**3:raise MigrationError('Source exceeds the 8 GiB migration limit.')
                input_=archive.extractfile(item);head=input_.read(16)
                database=head==b'SQLite format 3\0';suffix=next((s for s in ('-wal','-shm') if item.name.endswith(s)),None)
                document=head.startswith((b'%PDF-',b'\xff\xd8\xff',b'\x89PNG\r\n\x1a\n'))
                if not(database or suffix or document):continue
                key=item.name[:-len(suffix)] if suffix else item.name
                name=hashlib.sha256(key.encode()).hexdigest()+('.sqlite' if database or suffix else '.media')+(suffix or '')
                target=work/name
                if target.exists():raise MigrationError('Duplicate input entry or nonempty work directory. Use a new work directory.')
                with target.open('xb') as out:
                    out.write(head);shutil.copyfileobj(input_,out,65536)
                if target.stat().st_size!=item.size:raise MigrationError('Truncated source file.')
                if database:dbs.append(target)
                if document:media[item.name[len(prefix):]]=target
    matches=[]
    for path in dbs:
        with sqlite3.connect('file:'+str(path)+'?mode=ro',uri=True) as db:
            tables={r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if {'docs_table','doc_pages_table','pages_table','tags_table','doc_tags_table','stacks_table','kv_table','page_files_table'}<=tables:matches.append(path)
    if len(matches)!=1:raise MigrationError('Expected one supported Google Stack metadata database.')
    (work/"private-index.json").write_text(json.dumps({"database":str(matches[0]),"media":{key:str(value) for key,value in media.items()}}))
    return matches[0],media

def convert(db_path,media,timezone,active_type,excluded_type):
    if active_type==excluded_type:raise MigrationError('Active and excluded relationship codes must differ.')
    c=sqlite3.connect('file:'+str(db_path)+'?mode=ro',uri=True);c.row_factory=sqlite3.Row
    rows=lambda sql:list(c.execute(sql))
    docs=rows('SELECT * FROM docs_table ORDER BY rowid');source_tags={r['tag_id']:r for r in rows('SELECT * FROM tags_table')};catalog=rows('SELECT * FROM stacks_table ORDER BY rowid')
    if not 0<len(docs)<=1000 or len(catalog)>1000:raise MigrationError('Unsupported document/stack count.')
    graph=dict(format='org.stackdocs.library',version=2,schema=7,created_at=int(datetime.datetime.now().timestamp()*1000),documents=[],pages=[],stacks=[],memberships=[],suggestions=[],choices=[],decision_evidence={},files=[],details=[],tags=[],document_tags=[])
    payloads={};catalog_ids=set();names=set();counts=collections.Counter()
    for stack in catalog:
        if stack['stack_id'] not in source_tags:raise MigrationError('Stack name is missing.')
        name=clean(source_tags[stack['stack_id']]['tag_name'].strip())
        if name.casefold() in names:raise MigrationError('Duplicate category names need explicit reconciliation.')
        names.add(name.casefold());catalog_ids.add(stack['stack_id'])
        color=stack['color']
        # Preserve ARGB when present. Original palette enum codes stay in source evidence.
        if not isinstance(color,int) or not -2**31<=color<0:color=-1774110
        graph['stacks'].append(dict(id=stable('stack',stack['stack_id']),name=name,color=color,visible=int(bool(stack['show_on_home']))))
    by_basename=collections.defaultdict(list)
    for name,path in media.items():by_basename[pathlib.PurePosixPath(name).stem].append(path)
    source_pages={r['page_id']:r for r in rows('SELECT * FROM pages_table')}
    all_links=rows('SELECT * FROM doc_pages_table ORDER BY doc_id,tag_type');links=collections.defaultdict(list)
    for row in all_links:links[row['doc_id']].append(row)
    kv=collections.defaultdict(list)
    for row in rows('SELECT * FROM kv_table ORDER BY rowid'):kv[row['doc_id']].append(row)
    tags=collections.defaultdict(list)
    for row in rows('SELECT * FROM doc_tags_table ORDER BY rowid'):tags[row['doc_id']].append(row)
    page_files=collections.defaultdict(list)
    for row in rows('SELECT * FROM page_files_table ORDER BY page_version'):page_files[row['page_id']].append(row)
    for doc in docs:
        old=doc['doc_id'];id_=stable('document',old);title=clean(doc['doc_name'].strip());notes=doc['note'] or ''
        if title!=doc['doc_name']:counts['outer_whitespace_titles_normalized']+=1
        if units(notes)>20000:raise MigrationError('A note exceeds the archive contract.')
        local_date=doc['doc_scan_date'];added=datetime.datetime.strptime(local_date,'%Y-%m-%d %H:%M:%S').replace(tzinfo=ZoneInfo(timezone))
        pages=links[old]
        if not 0<len(pages)<=1000 or [p['tag_type'] for p in pages]!=list(range(len(pages))):raise MigrationError('Source page order is incomplete/ambiguous.')
        if '/files/' not in doc['file_path']:raise MigrationError('Unsupported source file path.')
        key='f/'+doc['file_path'].split('/files/',1)[1]
        if key not in media:raise MigrationError('A referenced PDF is missing. No partial archive is produced.')
        source=media[key];info=subprocess.run(['pdfinfo',str(source)],capture_output=True,text=True)
        size=next((int(line.split(':')[1]) for line in info.stdout.splitlines() if line.startswith('Pages:')),None)
        if info.returncode or size!=len(pages):raise MigrationError('A source PDF is damaged or its page count disagrees.')
        graph['documents'].append(dict(id=id_,title=title,mime='application/pdf',added_at=int(added.timestamp()*1000),page_count=len(pages),initial_page_count=len(pages),source_scan_id=None,title_user_edited=1,title_origin='manual',filing_origin='manual',title_decision=1,filing_decision=1,notes=notes,notes_revision=int(bool(notes)),metadata_revision=1))
        def original(path,file):
            if path in payloads:return
            digest=hashlib.sha256()
            with file.open('rb') as data:
                for chunk in iter(lambda:data.read(65536),b''):digest.update(chunk)
            payloads[path]=file;graph['files'].append(dict(path=path,length=file.stat().st_size,sha256=digest.hexdigest()))
        original('originals/'+id_+'/source',source)
        page_audit=[]
        for index,link in enumerate(pages):
            if link['page_id'] not in source_pages:raise MigrationError('A page record is missing.')
            p=source_pages[link['page_id']]
            graph['pages'].append(dict(id=stable('page',p['page_id']),document_id=id_,position=index,source_page=index,rotation=0,crop='',enhancement='original',ocr_text='',ocr_state='pending',ocr_engine='',language='eng',source_file='source',flatten=0,content_revision=0,text_revision=-1,ocr_error='',ocr_config='',text_config=''))
            path_key='f/'+p['image_path'].split('/files/',1)[1] if '/files/' in p['image_path'] else ''
            if path_key not in media:raise MigrationError('A locally referenced page original is missing.')
            path=media[path_key]
            with path.open('rb') as input_:
                if input_.read(3)!=b'\xff\xd8\xff':raise MigrationError('Retained source image is not JPEG; keep the source backup for a compatible converter.')
            name=stable('original',p['page_id'])+'.jpg';original('originals/'+id_+'/'+name,path)
            retained=[name]
            page_audit.append(dict(page_id=p['page_id'],rotation=p['rotation'],color_enhancement=p['color_enhancement'],crop=[p[k] for k in ('top_left_x','top_left_y','top_right_x','top_right_y','bottom_right_x','bottom_right_y','bottom_left_x','bottom_left_y')],crop_edited=p['crop_edited'],enhancement_edited=p['enhancement_edited'],page_source=p['page_source'],retained=retained,source_version_records=[[f['page_version'],f['file_uuid']] for f in page_files[p['page_id']]]))
        def detail(key,label,value,state,evidence):
            clean(label)
            if not isinstance(value,str) or not value.strip() or units(value)>2000 or '\0' in value:raise MigrationError('A detail has an empty/unsupported value; retain the source for manual review.')
            evidence=compact(evidence)
            if units(evidence)>8192:raise MigrationError('Source provenance exceeds 8 KiB. No silent truncation.')
            graph['details'].append(dict(document_id=id_,field_id='legacy:'+stable('field',key),kind='text',label=label,value=value,qualifier='',origin='manual',state=state,evidence=evidence,revision=1))
        for row in kv[old]:
            if row['deleted'] not in (0,1):raise MigrationError('Unknown original detail deletion state.')
            value=row['value'];empty=not value or not value.strip()
            if empty:counts['empty_source_details_preserved']+=1
            detail(old+':kv:'+row['key'],row['key'],'(empty in Google Stack)' if empty else value,'removed' if row['deleted'] else 'saved',dict(source='Google Stack',source_value_type=row['value_type'],source_value=value,source_location=base64.b64encode(row['location'] or b'').decode(),source_timestamp=base64.b64encode(row['timestamp'] or b'').decode()))
            counts['removed_details' if row['deleted'] else 'saved_details']+=1
        unmapped=[]
        for relation in tags[old]:
            tag=source_tags.get(relation['tag_id'])
            if tag is None:raise MigrationError('A referenced tag is missing.')
            type_=relation['tag_type'];is_stack=tag['tag_id'] in catalog_ids
            if type_==active_type and is_stack:graph['memberships'].append(dict(document_id=id_,stack_id=stable('stack',tag['tag_id'])));continue
            evidence=dict(source='Google Stack',source_tag_id=tag['tag_id'],source_tag_group=tag['tag_group'],source_relation_type=type_)
            if type_==excluded_type:
                detail(old+':tag:'+tag['tag_id'],'Excluded stack' if is_stack else 'Excluded tag',tag['tag_name'],'removed',evidence);counts['excluded_relations']+=1
            elif type_==active_type:
                detail(old+':tag:'+tag['tag_id'],'Imported tag',tag['tag_name'],'saved',evidence);counts['imported_labels']+=1
            else:
                if is_stack:raise MigrationError('Unknown category relationship code; confirm its meaning first.')
                unmapped.append(dict(name=tag['tag_name'],**evidence));counts['unmapped_auxiliary_relations']+=1
        detail(old+':source','Original scan date',local_date,'saved',dict(source='Google Stack',source_document_id=old,source_title=doc['doc_name'],source_category_names=[dict(id=t['tag_id'],name=t['tag_name']) for t in source_tags.values() if t['tag_id'] in catalog_ids and t['tag_name']!=t['tag_name'].strip()],timezone_interpretation=timezone,source_name_status=doc['name_status'],source_name_strategy=doc['name_strategy'],source_document_version=doc['doc_version'],source_catalog_settings=[dict(id=r['stack_id'],color=r['color'],visible=r['show_on_home']) for r in catalog],source_need_to_file=doc['need_to_file'],unmapped_auxiliary_relations=unmapped))
        chunks=[[]]
        for audit in page_audit:
            if units(compact(dict(source='Google Stack',pages=chunks[-1]+[audit])))>8192:chunks.append([])
            chunks[-1].append(audit)
        for index,chunk in enumerate(chunks):
            detail(old+':page-edits:'+str(index),'Original page edits'+(' · '+str(index+1) if len(chunks)>1 else ''),str(len(chunk))+' pages · preserved PDF snapshot','saved',dict(source='Google Stack',pages=chunk))
        graph['decision_evidence'][id_]=hashlib.sha256(('legacy:'+old).encode()).hexdigest()
        graph['suggestions'].append(dict(document_id=id_,generation=0,status='pending',input='',evidence='',rule='google-stack-local-migration',title_revision=1,filing_revision=1,payload=compact(dict(title='',partial=False,truncated=False,stacks={})),title_state='accepted',filing_state='accepted'))
    known={d['doc_id'] for d in docs}
    if set(links)-known or set(kv)-known or set(tags)-known:raise MigrationError('Orphaned source metadata needs review; no silent omission.')
    if len(graph['pages'])>10000 or len(graph['details'])>20000:raise MigrationError('Archive metadata limit exceeded.')
    counts.update(documents=len(docs),pages=len(graph['pages']),stacks=len(graph['stacks']),memberships=len(graph['memberships']),notes=sum(bool(d['notes']) for d in graph['documents']),retained_jpeg_files=len(graph['files'])-len(docs))
    c.close();return graph,payloads,dict(counts)

def write_archive(graph,payloads,destination):
    manifest=compact(graph).encode();
    if len(manifest)>32*1024**2:raise MigrationError('Manifest exceeds 32 MiB.')
    if sum(f['length'] for f in graph['files'])>8*1024**3:raise MigrationError('Archive exceeds 8 GiB.')
    part=destination.with_name(destination.name+'.partial')
    if part.exists() or destination.exists():raise MigrationError('Output already exists. Choose a new destination.')
    try:
        with part.open('xb') as out:
            out.write(b'STACKBK\x01'+struct.pack('>i',len(manifest))+hashlib.sha256(manifest).digest()+manifest)
            for entry in graph['files']:
                name=entry['path'].encode();out.write(struct.pack('>H',len(name))+name+struct.pack('>q',entry['length']))
                with payloads[entry['path']].open('rb') as input_:shutil.copyfileobj(input_,out,65536)
            out.flush();os.fsync(out.fileno())
        part.rename(destination)
    finally:
        if part.exists():part.unlink()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--input',type=pathlib.Path,required=True);p.add_argument('--output',type=pathlib.Path,required=True);p.add_argument('--workdir',type=pathlib.Path,required=True);p.add_argument('--timezone',required=True);p.add_argument('--active-tag-type',type=int,required=True);p.add_argument('--excluded-tag-type',type=int,required=True);a=p.parse_args();os.umask(0o077)
    script=pathlib.Path(__file__).resolve();repo=next((parent for parent in script.parents if (parent/'.git').exists()),script.parents[1])
    for path in (a.output,a.workdir):
        if path.resolve().is_relative_to(repo):p.error('Private migration output/work files must remain outside the repository.')
    try:
        db,media=unpack(a.input,a.workdir);graph,files,report=convert(db,media,a.timezone,a.active_tag_type,a.excluded_tag_type);write_archive(graph,files,a.output)
        report.update(archive_version=2,schema=7,source_kind='User-authorized original Google Stack Android backup',source_details='Exact text plus original type/location/timestamp provenance; no inferred currency/date retyping',category_mapping='Explicit confirmed active/excluded enum codes',timezone_interpretation=a.timezone,private_output_bytes=a.output.stat().st_size)
        a.output.with_suffix('.report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
    except MigrationError as error:
        raise SystemExit(str(error))
    except (ValueError,KeyError,sqlite3.Error,tarfile.TarError,OSError,EOFError,zlib.error):
        # Exceptions can include source paths/names. Keep CLI output content-free.
        raise SystemExit('Conversion stopped without importing anything. Check supported schema, exact file coverage, source relationship codes and limits locally. Private input remains intact.')
if __name__=='__main__':main()
