#!/usr/bin/env python3
"""Generate independently authored CC0, non-sensitive printable PDFs outside Git; no dependencies."""
import argparse,pathlib
p=argparse.ArgumentParser();p.add_argument('directory',type=pathlib.Path);a=p.parse_args();a.directory.mkdir(parents=True,exist_ok=True)
def pdf(name,pages):
    objects=[b'',b'',b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'];page_ids=[]
    for lines in pages:
        page_id=len(objects)+1;stream_id=page_id+1;page_ids.append(page_id)
        objects.append(f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Resources << /Font << /F1 3 0 R >> >> /Contents {stream_id} 0 R >>'.encode())
        commands=['BT /F1 18 Tf 40 750 Td 28 TL']
        for line in lines:
            safe=line.replace('\\','\\\\').replace('(','\\(').replace(')','\\)');commands.append('('+safe+') Tj T*')
        commands.append('ET');stream='\n'.join(commands).encode('ascii');objects.append(b'<< /Length '+str(len(stream)).encode()+b' >>\nstream\n'+stream+b'\nendstream')
    objects[0]=b'<< /Type /Catalog /Pages 2 0 R >>';objects[1]=('<< /Type /Pages /Count '+str(len(page_ids))+' /Kids ['+' '.join(f'{i} 0 R' for i in page_ids)+'] >>').encode()
    result=bytearray(b'%PDF-1.4\n');offsets=[0]
    for i,obj in enumerate(objects,1):offsets.append(len(result));result+=f'{i} 0 obj\n'.encode()+obj+b'\nendobj\n'
    xref=len(result);result+=f'xref\n0 {len(objects)+1}\n0000000000 65535 f \n'.encode()
    for offset in offsets[1:]:result+=f'{offset:010d} 00000 n \n'.encode()
    result+=f'trailer\n<< /Size {len(objects)+1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode();(a.directory/name).write_bytes(result)
pdf('P1-Invoice.pdf', [['Willow Credit Union','Synthetic document - no personal information','Invoice date: 2026-10-08','Due date: 2026-11-08','Amount due: USD 24.50','Print this sheet for physical capture checks.'],['Later-page quartz lantern sentinel','Fine print: preserve the original and page order.','Synthetic reference only.']])
pdf('P1-Receipt.pdf',[['Harbor Energy Company','Synthetic receipt','Date: 2026-10-12','Total: CAD 18.40','No personal information.']])
pdf('P1-Ambiguous.pdf',[['Synthetic ambiguous example','Date: 05/06/2026','Total: $39.95','The numeric date should abstain.','Bare dollar currency defaults to USD.']])
pdf('P1-Labeled-Details.pdf',[['Synthetic appointment summary','Client #: 000741','Patient #','P-0042','Staff Name: Alex Sample','Date: July 31, 2026','Balance due:','USD 0.00','Total Amount: $75.40','Invoice number: INV-0088','Account ID: A-0023']])
(a.directory/'P1-Unsupported.txt').write_text('Synthetic unsupported import format.\n')
(a.directory/'Start-here.txt').write_text('Stack test files - synthetic, no personal information\n\nOpen Stack Checks, use + to import a PDF from Download/Stack-Test-Files.\nUse Stack Checks for deletion, archive/restore, batch, permission and lock tests.\nMain Stack now contains your migrated documents.\n\nP1-Labeled-Details.pdf: detected values should save without Apply. Check Client #\n000741, Patient # P-0042, Staff name Alex Sample, Date 2026-07-31, Amount due\n0.00 USD, Total 75.40 USD, Invoice # INV-0088 and Account # A-0023.\nEdit a value, remove another, reopen/reprocess and check your changes remain.\nThe three-dot menu offers Edit, Copy, Remove and Source for detected values.\n\nP1-Receipt.pdf: explicit CAD must remain CAD (it overrides the dollar default).\nP1-Ambiguous.pdf: the numeric date should abstain; bare $ total uses USD.\nP1-Invoice.pdf: two pages; search for quartz lantern to find the later page.\nP1-Unsupported.txt: unsupported document import should show an error safely.\n\nThe remaining formal H1-H8 acceptance session is tracked in the project checklist.\n')
print('Created five CC0 synthetic fixtures and Start-here.txt in',a.directory)
