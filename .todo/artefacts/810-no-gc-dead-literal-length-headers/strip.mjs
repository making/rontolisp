import { readFileSync, writeFileSync } from 'node:fs';
const b = readFileSync(process.argv[2]);
const secs = [];
let i = 8;
while (i < b.length) {
  const id = b[i++]; let s = 0, sh = 0, by;
  do { by = b[i++]; s |= (by & 0x7f) << sh; sh += 7; } while (by & 0x80);
  secs.push({ id, payload: Buffer.from(b.subarray(i, i + s)) });
  i += s;
}
const uleb = n => { const o = []; do { let x = n & 0x7f; n >>>= 7; if (n) x |= 0x80; o.push(x); } while (n); return Buffer.from(o); };
const sleb = n => { const o = []; for (;;) { let x = n & 0x7f; n >>= 7; if ((n === 0 && !(x & 0x40)) || (n === -1 && (x & 0x40))) { o.push(x); break; } o.push(x | 0x80); } return Buffer.from(o); };

// --- rebuild the data segment without the 4-byte headers ---
const d = secs.find(s => s.id === 11).payload;
let j = 0; const rd = () => { let v = 0, sh2 = 0, c; do { c = d[j++]; v |= (c & 0x7f) << sh2; sh2 += 7; } while (c & 0x80); return v; };
rd(); rd(); j++; const base = rd(); j++; const segLen = rd();
const seg = d.subarray(j, j + segLen);
const lits = []; let p = 0;
while (p + 4 <= seg.length) { const L = seg.readUInt32LE(p); lits.push(seg.subarray(p + 4, p + 4 + L)); p += 4 + L; }
const map = new Map(); let oldC = base, newC = base;
for (const L of lits) { map.set(oldC + 4, newC); oldC += 4 + L.length; newC += L.length; }
const raw = Buffer.concat(lits);
secs.find(s => s.id === 11).payload = Buffer.concat([uleb(1), uleb(0), Buffer.from([0x41]), sleb(base), Buffer.from([0x0b]), uleb(raw.length), raw]);

// --- rewrite the address constant of every (addr,len) pair in the code section ---
const code = secs.find(s => s.id === 10).payload;
let hits = 0;
for (const [oldA, newA] of map) {
  const oa = sleb(oldA), na = sleb(newA);
  if (oa.length !== na.length) throw new Error('LEB width changed for ' + oldA);
  for (const L of lits) {
    const pat = Buffer.concat([Buffer.from([0x41]), oa, Buffer.from([0x41]), sleb(L.length)]);
    let at = 0;
    for (;;) { const k = code.indexOf(pat, at); if (k < 0) break; na.copy(code, k + 1); hits++; at = k + 1; }
  }
}
const out = [Buffer.from(b.subarray(0, 8))];
for (const s of secs) out.push(Buffer.from([s.id]), uleb(s.payload.length), s.payload);
writeFileSync(process.argv[3], Buffer.concat(out));
console.log('literals=' + lits.length, 'headerBytes=' + lits.length * 4, 'rewrites=' + hits);
