#!/usr/bin/env python3
"""Writes the synthetic PDFs for the PDF text layer checks into app/src/androidTest/assets/types/pdf/.

Developer tool for regenerating test files; nothing here runs in the app. Needs Pillow (for the image-only page).
"""
import os
import sys
import zlib

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "androidTest", "assets", "types", "pdf")
PNG = os.path.join(OUT, "..", "png", "synthetic.png")


def stream(dict_body: bytes, data: bytes, compress=False) -> bytes:
    if compress:
        data = zlib.compress(data, 9)
        dict_body += b" /Filter /FlateDecode"
    return b"<< " + dict_body + b" /Length %d >>\nstream\n" % len(data) + data + b"\nendstream"


def write_pdf(name, objs, root=1, version=b"1.4", trailer_extra=b"", tail=b"", out_dir=None):
    """objs: {number: bytes}. Classic xref table written last."""
    out = bytearray(b"%PDF-" + version + b"\n%\xe2\xe3\xcf\xd3\n")
    offsets = {}
    for n in sorted(objs):
        offsets[n] = len(out)
        out += b"%d 0 obj\n" % n + objs[n] + b"\nendobj\n"
    xref = len(out)
    top = max(objs) + 1
    out += b"xref\n0 %d\n" % top + b"0000000000 65535 f \n"
    for n in range(1, top):
        out += (b"%010d 00000 n \n" % offsets[n]) if n in offsets else b"0000000000 65535 f \n"
    out += b"trailer\n<< /Size %d /Root %d 0 R %s >>\nstartxref\n%d\n%%%%EOF\n" % (top, root, trailer_extra, xref)
    out += tail
    with open(os.path.join(out_dir or OUT, name), "wb") as f:
        f.write(out)
    print(name, len(out))


def esc(s: str) -> bytes:
    return s.encode("latin-1").replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)")


def page_objs(first, contents):
    """Catalog 1, Pages 2, fonts 3..8, then pages. contents: list of content-stream bytes (compressed or not)."""
    return None


FONT_RES = b"<< /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R /F4 6 0 R /F5 7 0 R /F6 9 0 R >> >>"


def fonts():
    return {
        3: b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        4: b"<< /Type /Font /Subtype /Type1 /BaseFont /Times-Roman /Encoding /WinAnsiEncoding >>",
        5: b"<< /Type /Font /Subtype /Type1 /BaseFont /Courier >>",
        # Differences: code 1 = fi ligature, 2 = Euro, 3 = endash
        6: b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Oblique /Encoding << /Type /Encoding /BaseEncoding /WinAnsiEncoding /Differences [1 /fi /Euro /endash] >> >>",
        7: b"<< /Type /Font /Subtype /Type1 /BaseFont /Times-Italic /Encoding /MacRomanEncoding >>",
        # Type0, Identity-H, ToUnicode maps glyph ids to letters (no embedded program: only the text layer matters)
        9: b"<< /Type /Font /Subtype /Type0 /BaseFont /Synthetic /Encoding /Identity-H /DescendantFonts [10 0 R] /ToUnicode 11 0 R >>",
        10: b"<< /Type /Font /Subtype /CIDFontType2 /BaseFont /Synthetic /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /DW 600 /W [1 [500 600 700]] >>",
        11: stream(b"", b"""/CIDInit /ProcSet findresource begin 12 dict begin begincmap
/CMapName /Adobe-Identity-UCS def /CMapType 2 def
1 begincodespacerange <0000> <FFFF> endcodespacerange
2 beginbfchar <0001> <0054> <0002> <0079> endbfchar
2 beginbfrange <0010> <0019> <0030> <0020> <0025> [<0061> <0062> <0063> <0064> <0065> <0066>] endbfrange
endcmap CMapName currentdict /CMap defineresource pop end end""", compress=True),
    }


PAGE_TEXT = [
    # page 1: several fonts, kerning arrays, a line break by Td and by T*, a TJ gap that means a space
    b"""BT /F1 14 Tf 72 720 Td (Stack types: born digital page one) Tj
0 -20 Td /F2 12 Tf (Times Roman line with caf\\351 and na\\357ve) Tj
0 -20 Td /F3 10 Tf (Courier fixed width: 1234567890) Tj
0 -20 Td /F4 12 Tf (\\001rst of\\001ce costs \\002 5 \\003 a long dash) Tj
0 -20 Td /F5 12 Tf (Mac Roman: \\216 \\217 \\232) Tj
0 -20 Td /F1 12 Tf 14 TL [(Ker) 40 (ned) -300 (words) 20 (stay) -300 (apart)] TJ T*
(Next line by T-star ) Tj (follows on the same line) Tj ET
BT /F6 12 Tf 72 500 Td <0001> Tj <00100011> Tj <0002> Tj <0020> Tj <0021> Tj ET""",
    b"""BT /F1 12 Tf 72 720 Td (Second page of the born digital file, with enough letters to count as text.) Tj
0 -16 Td (Invoice 2026-1126 total 1,234.50 due next month.) Tj ET""",
    b"""BT /F2 12 Tf 72 720 Td (Third page: types and kinds of fonts are all plain text here.) Tj ET""",
]


def born_digital(name, compress, objstm=False):
    objs = {1: b"<< /Type /Catalog /Pages 2 0 R >>"}
    objs.update(fonts())
    kids = []
    for i, text in enumerate(PAGE_TEXT):
        pn, cn = 20 + i * 2, 21 + i * 2
        kids.append(b"%d 0 R" % pn)
        objs[pn] = b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents %d 0 R >>" % cn
        objs[cn] = stream(b"", text, compress)
    objs[2] = b"<< /Type /Pages /Kids [" + b" ".join(kids) + b"] /Count %d /Resources " % len(kids) + FONT_RES + b" >>"
    if not objstm:
        write_pdf(name, objs)
        return
    # PDF 1.5: non-stream objects packed in an object stream, a cross-reference stream.
    packed = [n for n in sorted(objs) if not objs[n].startswith(b"<< ") or b"stream" not in objs[n]]
    packed = [n for n in packed if b"\nstream\n" not in objs[n]]
    body, head = b"", b""
    for n in packed:
        head += b"%d %d " % (n, len(body))
        body += objs[n] + b"\n"
    out = bytearray(b"%PDF-1.5\n%\xe2\xe3\xcf\xd3\n")
    offsets = {}
    for n in sorted(objs):
        if n not in packed:
            offsets[n] = len(out)
            out += b"%d 0 obj\n" % n + objs[n] + b"\nendobj\n"
    stm_num = max(objs) + 1
    offsets[stm_num] = len(out)
    out += b"%d 0 obj\n" % stm_num + stream(b"/Type /ObjStm /N %d /First %d" % (len(packed), len(head)), head + body, True) + b"\nendobj\n"
    xnum = stm_num + 1
    offsets[xnum] = len(out)
    rows = bytearray()
    for n in range(0, xnum + 1):
        if n in packed:
            rows += bytes([2]) + stm_num.to_bytes(4, "big") + packed.index(n).to_bytes(2, "big")
        elif n in offsets:
            rows += bytes([1]) + offsets[n].to_bytes(4, "big") + (0).to_bytes(2, "big")
        else:
            rows += bytes([0]) + (0).to_bytes(4, "big") + (65535).to_bytes(2, "big")
    out += b"%d 0 obj\n" % xnum + stream(b"/Type /XRef /Size %d /W [1 4 2] /Root 1 0 R" % (xnum + 1), bytes(rows), True) + b"\nendobj\n"
    out += b"startxref\n%d\n%%%%EOF\n" % offsets[xnum]
    with open(os.path.join(OUT, name), "wb") as f:
        f.write(out)
    print(name, len(out))


def image_object():
    from PIL import Image
    im = Image.open(PNG).convert("RGB")
    raw = zlib.compress(im.tobytes(), 9)
    return im.size, b"<< /Type /XObject /Subtype /Image /Width %d /Height %d /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode /Length %d >>\nstream\n" % (im.size[0], im.size[1], len(raw)) + raw + b"\nendstream"


def image_only(name, with_text_first=False):
    (w, h), img = image_object()
    objs = {1: b"<< /Type /Catalog /Pages 2 0 R >>", 5: img, 6: b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"}
    kids = []
    n = 10
    if with_text_first:
        objs[n] = b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 200] /Contents %d 0 R /Resources << /Font << /F1 6 0 R >> >> >>" % (n + 1)
        objs[n + 1] = stream(b"", b"BT /F1 18 Tf 20 100 Td (Text layer page: these types of words are read without OCR.) Tj ET", True)
        kids.append(b"%d 0 R" % n)
        n += 2
    objs[n] = b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 200] /Contents %d 0 R /Resources << /XObject << /Im0 5 0 R >> >> >>" % (n + 1)
    objs[n + 1] = stream(b"", b"q 600 0 0 200 0 0 cm /Im0 Do Q", True)
    kids.append(b"%d 0 R" % n)
    objs[2] = b"<< /Type /Pages /Kids [" + b" ".join(kids) + b"] /Count %d >>" % len(kids)
    write_pdf(name, objs)


def good_page_objs():
    objs = {1: b"<< /Type /Catalog /Pages 2 0 R >>", 3: b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"}
    objs[10] = b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 11 0 R /Resources << /Font << /F1 3 0 R >> >> >>"
    objs[11] = stream(b"", b"BT /F1 12 Tf 72 720 Td (Hostile test file, good page: the types survive.) Tj ET", True)
    objs[2] = b"<< /Type /Pages /Kids [10 0 R] /Count 1 >>"
    return objs


def hostile_deep():
    objs = good_page_objs()
    # an object nested 200k deep, a page tree 100k levels deep, and a content stream with 300k nested arrays
    objs[30] = b"[" * 200000
    objs[31] = b"<<" + b"/A <<" * 100000
    objs[11] = stream(b"", b"BT /F1 12 Tf 72 720 Td (Hostile test file, good page: the types survive.) Tj ET\n" + b"[" * 300000 + b" 1 2 3 TJ\n" + b"<< /A " * 100000, True)
    write_pdf("hostile-deep-nesting.pdf", objs)


def hostile_huge():
    objs = good_page_objs()
    # about 1 GiB of zeros in 1 MiB of file; and a stream whose /Length claims far more than the file
    c = zlib.compressobj(9)
    parts = [c.compress(bytes(1 << 20)) for _ in range(1024)]
    parts.append(c.flush())
    big = b"".join(parts)
    objs[40] = b"<< /Filter /FlateDecode /Length %d >>\nstream\n" % len(big) + big + b"\nendstream"
    objs[41] = b"<< /Length 99999999999 >>\nstream\nshort\nendstream"
    objs[12] = b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents [40 0 R 11 0 R] /Resources << /Font << /F1 3 0 R >> >> >>"
    objs[2] = b"<< /Type /Pages /Kids [10 0 R 12 0 R] /Count 2 >>"
    write_pdf("hostile-huge-stream.pdf", objs)


def hostile_xref_loop():
    objs = good_page_objs()
    # page tree cycles: the Pages node is its own kid, a Kids list repeats itself; xref /Prev points at itself
    objs[2] = b"<< /Type /Pages /Kids [2 0 R 20 0 R 10 0 R 20 0 R] /Count 1 /Parent 2 0 R >>"
    objs[20] = b"<< /Type /Pages /Kids [2 0 R 20 0 R] /Parent 2 0 R >>"
    out = bytearray()
    write_pdf("hostile-xref-loop.pdf", objs, trailer_extra=b"")
    path = os.path.join(OUT, "hostile-xref-loop.pdf")
    data = open(path, "rb").read()
    pos = data.rfind(b"startxref\n")
    xref = int(data[pos + 10:].split()[0])
    data = data.replace(b"/Root 1 0 R", b"/Root 1 0 R /Prev %d" % xref, 1)
    # the xref offset moved by the inserted bytes; leave startxref pointing at the (now wrong) place on purpose
    data += b"xref\n0 1\n0000000000 65535 f \ntrailer\n<< /Size 1 /Prev %d >>\nstartxref\n%d\n%%%%EOF\n" % (xref, xref)
    open(path, "wb").write(data)
    print("hostile-xref-loop.pdf", len(data))


def hostile_flate():
    objs = good_page_objs()
    good = zlib.compress(b"BT /F1 12 Tf 72 600 Td (Broken compression page: the first part is readable and the types survive.) Tj ET " * 300, 9)
    cut = bytearray(good)
    for i in range(len(cut) // 2, len(cut) // 2 + 40):
        cut[i] ^= 0xA5
    objs[12] = b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 13 0 R /Resources << /Font << /F1 3 0 R >> >> >>"
    objs[13] = b"<< /Filter /FlateDecode /Length %d >>\nstream\n" % len(cut) + bytes(cut) + b"\nendstream"
    objs[14] = b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 15 0 R /Resources << /Font << /F1 3 0 R >> >> >>"
    objs[15] = b"<< /Filter /FlateDecode /Length 64 >>\nstream\n" + bytes((i * 37 + 11) & 0xFF for i in range(64)) + b"\nendstream"
    objs[2] = b"<< /Type /Pages /Kids [10 0 R 12 0 R 14 0 R] /Count 3 >>"
    write_pdf("hostile-broken-flate.pdf", objs)


SCAN_WORDS = ["alpaca", "bobcat", "cormorant", "dolphin", "echidna", "flamingo", "gazelle", "hedgehog", "iguana", "jackal", "kingfisher", "lemur",
              "meerkat", "narwhal", "ostrich", "pelican", "quail", "raccoon", "salamander", "tortoise", "urchin", "vulture", "walrus", "yak",
              "zebra", "badger", "cheetah", "dragonfly", "eagle", "ferret"]
SCAN_LINES = ["The quarterly statement lists every payment received before the end of the month.",
              "Please keep this copy with the other papers of the household for seven years.",
              "Insurance premiums, electricity, water and the telephone are paid by transfer.",
              "A signed receipt is attached to each invoice that arrived by post this season.",
              "The garden shed was repaired in spring and the roofer sent his final bill.",
              "Doctor visits and pharmacy receipts are filed together in the medical folder.",
              "The bank confirmed the new standing order for the rent starting next month.",
              "Tax advisers asked for the donation letters and the school fee certificates.",
              "Winter tyres were fitted and the car passed its yearly inspection without remarks.",
              "All warranty cards for the kitchen appliances are in the blue binder on the shelf.",
              "The city council sent the notice about the waste collection calendar.",
              "A refund of the overpaid energy bill is expected within two weeks.",
              "Travel tickets, hotel bookings and the insurance certificate are listed below.",
              "The accountant will review the figures once the remaining receipts have arrived.",
              "Library fees, club dues and magazine subscriptions are paid every January.",
              "Our neighbour kept a spare key and the list of emergency telephone numbers.",
              "The mortgage statement shows the remaining balance and the interest for the year.",
              "Please send the corrected invoice and quote the reference number printed above.",
              "Passport renewal needs two photographs, the old passport and the payment slip.",
              "The pension office wrote to confirm the date of the first payment."]


def scanned_pdf(name, pages=30, lines=20):
    """An image-only PDF of `pages` pages, each a dense page of text that only OCR can read (page n carries SCAN_WORDS[n-1])."""
    from PIL import Image, ImageDraw, ImageFont
    font = ImageFont.truetype("/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf", 38)
    big = ImageFont.truetype("/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf", 60)
    objs = {1: b"<< /Type /Catalog /Pages 2 0 R >>"}
    kids = []
    n = 10
    for i in range(pages):
        im = Image.new("L", (1275, 1650), 255)
        d = ImageDraw.Draw(im)
        d.text((90, 90), "Household archive %02d %s" % (i + 1, SCAN_WORDS[i % len(SCAN_WORDS)]), font=big, fill=0)
        for k in range(lines):
            d.text((90, 220 + k * 62), SCAN_LINES[(i + k) % len(SCAN_LINES)], font=font, fill=0)
        raw = zlib.compress(im.tobytes(), 9)
        objs[n] = b"<< /Type /XObject /Subtype /Image /Width 1275 /Height 1650 /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode /Length %d >>\nstream\n" % len(raw) + raw + b"\nendstream"
        objs[n + 1] = b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents %d 0 R /Resources << /XObject << /Im0 %d 0 R >> >> >>" % (n + 2, n)
        objs[n + 2] = stream(b"", b"q 612 0 0 792 0 0 cm /Im0 Do Q", True)
        kids.append(b"%d 0 R" % (n + 1))
        n += 3
    objs[2] = b"<< /Type /Pages /Kids [" + b" ".join(kids) + b"] /Count %d >>" % len(kids)
    # not under types/pdf: the type checks read every file there with the whole-file reader, which is exactly what these are too long for
    sources = os.path.join(OUT, "..", "..", "sources") if OUT.endswith("pdf") else OUT
    os.makedirs(sources, exist_ok=True)
    write_pdf(name, objs, out_dir=sources)


if __name__ == "__main__":
    if len(sys.argv) > 1:
        OUT = sys.argv[1]
    born_digital("born-digital.pdf", compress=False)
    born_digital("born-digital-objstm.pdf", compress=True, objstm=True)
    image_only("image-only.pdf")
    image_only("mixed-text-and-image.pdf", with_text_first=True)
    hostile_deep()
    hostile_huge()
    hostile_xref_loop()
    hostile_flate()
    scanned_pdf("scanned-30-pages.pdf")
    scanned_pdf("scanned-6-pages.pdf", pages=6, lines=8)
