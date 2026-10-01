import sys, zipfile, struct
src, dst, dex = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(src)
entries = [(i, zin.read(i.filename)) for i in zin.infolist()]
entries.append((None, open(dex, 'rb').read()))
with open(dst, 'wb') as f:
    zout = zipfile.ZipFile(f, 'w')
    for info, data in entries:
        if info is None:
            zi = zipfile.ZipInfo('classes.dex', date_time=(2026, 1, 1, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
        else:
            zi = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            stored = info.compress_type == zipfile.ZIP_STORED or info.filename == 'resources.arsc' or info.filename.endswith('.png')
            zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
        zi.external_attr = 0
        if zi.compress_type == zipfile.ZIP_STORED:
            # pad the local header "extra" so file data starts on a 4-byte boundary
            align = 4096 if zi.filename.endswith('.so') else 4
            off = f.tell() + 30 + len(zi.filename.encode())
            pad = (align - off % align) % align
            zi.extra = b'\x00' * pad
        zout.writestr(zi, data)
    zout.close()
# verify alignment
z = zipfile.ZipFile(dst)
with open(dst, 'rb') as f:
    for i in z.infolist():
        f.seek(i.header_offset); h = f.read(30)
        n, e = struct.unpack('<HH', h[26:30]); data_off = i.header_offset + 30 + n + e
        if i.compress_type == zipfile.ZIP_STORED and data_off % 4 != 0:
            raise SystemExit('misaligned ' + i.filename)
print('aligned ok', len(z.infolist()), 'entries')
