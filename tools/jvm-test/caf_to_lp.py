"""Split an ALAC .caf into a 24-byte cookie file and u32be-length-prefixed frames (.lp)."""
import struct, sys

def varint(b, i):
    v = 0
    while True:
        c = b[i]; i += 1
        v = (v << 7) | (c & 0x7f)
        if not c & 0x80:
            return v, i

data = open(sys.argv[1], 'rb').read()
assert data[:4] == b'caff'
i = 8
chunks = {}
while i < len(data):
    typ = data[i:i + 4]; size = struct.unpack('>q', data[i + 4:i + 12])[0]
    body = data[i + 12:i + 12 + size] if size >= 0 else data[i + 12:]
    chunks[typ] = body
    i += 12 + (size if size >= 0 else len(body))
kuki = chunks[b'kuki']
cookie = kuki[-24:] if len(kuki) >= 24 else kuki
if b'alac' in kuki:  # 'frma'/'alac' atoms wrap the config
    j = kuki.rindex(b'alac') + 4 + 4
    cookie = kuki[j:j + 24]
pakt = chunks[b'pakt']
n = struct.unpack('>q', pakt[0:8])[0]
p = 24
sizes = []
for _ in range(n):
    s, p = varint(pakt, p)
    sizes.append(s)
audio = chunks[b'data'][4:]  # skip edit count
with open(sys.argv[2], 'wb') as f:
    f.write(cookie)
with open(sys.argv[3], 'wb') as f:
    off = 0
    for s in sizes:
        f.write(struct.pack('>I', s)); f.write(audio[off:off + s]); off += s
print('frames', len(sizes), 'cookie', cookie.hex())
