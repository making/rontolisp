import { readFileSync, writeFileSync } from 'node:fs';
const b = readFileSync(process.argv[2]);
const secs = []; let i = 8;
while (i < b.length) { const id = b[i++]; let s = 0, sh = 0, by;
  do { by = b[i++]; s |= (by & 0x7f) << sh; sh += 7; } while (by & 0x80);
  secs.push({ id, payload: Buffer.from(b.subarray(i, i + s)) }); i += s; }
const uleb = n => { const o = []; do { let x = n & 0x7f; n >>>= 7; if (n) x |= 0x80; o.push(x); } while (n); return Buffer.from(o); };
let code = secs.find(s => s.id === 10).payload;
// split into bodies
let j = 0; const rd = () => { let v=0,sh=0,c; do{c=code[j++]; v|=(c&0x7f)<<sh; sh+=7;}while(c&0x80); return v; };
const n = rd(); const bodies = [];
for (let k = 0; k < n; k++) { const sz = rd(); bodies.push(Buffer.from(code.subarray(j, j + sz))); j += sz; }
let hits = 0;
for (let k = 0; k < bodies.length; k++) {
  let body = bodies[k];
  for (;;) {
    // local.tee S | i64.const -2^31 | lt_s | if | unreachable | end | local.get S | i64.const 2^31-1 | gt_s | if | unreachable | end | local.get S
    let found = -1, slot = -1;
    for (let p = 0; p + 28 <= body.length; p++) {
      if (body[p] !== 0x22) continue;
      const s = body[p + 1];
      const pat = Buffer.from([0x22, s, 0x42, 0x80, 0x80, 0x80, 0x80, 0x78, 0x53, 0x04, 0x40, 0x00, 0x0b,
        0x20, s, 0x42, 0xff, 0xff, 0xff, 0xff, 0x07, 0x55, 0x04, 0x40, 0x00, 0x0b, 0x20, s]);
      if (body.subarray(p, p + 28).equals(pat)) { found = p; slot = s; break; }
    }
    if (found < 0) break;
    const neu = Buffer.from([0x22, slot, 0x20, slot, 0xa7, 0xac, 0x52, 0x04, 0x40, 0x00, 0x0b, 0x20, slot]);
    body = Buffer.concat([body.subarray(0, found), neu, body.subarray(found + 28)]);
    hits++;
  }
  bodies[k] = body;
}
const parts = [uleb(n)];
for (const bd of bodies) { parts.push(uleb(bd.length), bd); }
secs.find(s => s.id === 10).payload = Buffer.concat(parts);
const out = [Buffer.from(b.subarray(0, 8))];
for (const s of secs) out.push(Buffer.from([s.id]), uleb(s.payload.length), s.payload);
writeFileSync(process.argv[3], Buffer.concat(out));
console.log('s32 guards rewritten:', hits);
