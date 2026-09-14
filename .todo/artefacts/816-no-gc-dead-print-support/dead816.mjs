// .todo/816 hand-verification: rewrite the EMITTED binary of a printing --no-gc
// module, dropping the data bytes no code can reach and the heap bracket a
// module that bumps nothing never needs.
//
// Liveness is read off the CODE, not off the data layout: after the printed-
// literal fold a segment is a MIXTURE of headerless literals (written as two
// constants) and headered ones, so it cannot be walked structurally.
//   - `i32.const A; i32.const L; call F` with A inside it    -> [A, A+L) is live
//   - any other in-segment `i32.const A`                     -> [A, A+4+[A]) is live
//     (a header pointer: keep the header and the bytes it measures)
// Everything else in the segment is unreachable.
//
// This is a MEASUREMENT PROBE, not a pass: it rewrites one emitted module so the
// saving can be counted and the result re-run. Usage: node dead816.mjs in out
import { readFileSync, writeFileSync } from 'node:fs';

const b = readFileSync(process.argv[2]);
const uleb = (n) => { const o = []; do { let x = n & 0x7f; n >>>= 7; if (n) x |= 0x80; o.push(x); } while (n); return Buffer.from(o); };
const secs = [];
let i = 8;
while (i < b.length) {
  const id = b[i++]; let s = 0, sh = 0, by;
  do { by = b[i++]; s |= (by & 0x7f) << sh; sh += 7; } while (by & 0x80);
  secs.push({ id, payload: Buffer.from(b.subarray(i, i + s)) });
  i += s;
}
const sec = (id) => secs.find((s) => s.id === id);

const d = sec(11).payload;
let j = 0;
const rd = () => { let v = 0, sh = 0, c; do { c = d[j++]; v |= (c & 0x7f) << sh; sh += 7; } while (c & 0x80); return v; };
rd(); rd(); j++; const base = rd(); j++; const segLen = rd();
const seg = Buffer.from(d.subarray(j, j + segLen));
const end = base + segLen;

// --- every i32.const in the code, with its byte offset and encoded width ---
const code = sec(10).payload;
const cs = [];
for (let p = 0; p < code.length; p++) {
  if (code[p] !== 0x41) continue;
  let v = 0, sh = 0, c, q = p + 1;
  do { c = code[q++]; v |= (c & 0x7f) << sh; sh += 7; } while (c & 0x80);
  cs.push({ at: p, end: q, v });
}

const live = new Uint8Array(segLen);
const mark = (a, n) => { for (let k = a; k < a + n; k++) if (k >= base && k < end) live[k - base] = 1; };
const inSeg = (v) => v >= base && v < end;
const paired = new Set();
for (let k = 0; k + 1 < cs.length; k++) {
  // the two-constant write is `i32.const A; i32.const L; call F`
  if (cs[k].end !== cs[k + 1].at || code[cs[k + 1].end] !== 0x10) continue;
  if (!inSeg(cs[k].v) || cs[k].v + cs[k + 1].v > end) continue;
  mark(cs[k].v, cs[k + 1].v);
  paired.add(cs[k].at);
  paired.add(cs[k + 1].at);   // the LENGTH operand is not an address
}
for (const c of cs) {
  if (!inSeg(c.v) || paired.has(c.at)) continue;
  mark(c.v, 4 + seg.readUInt32LE(c.v - base)); // a header pointer
}
let deadBytes = 0;
for (let k = 0; k < segLen; k++) if (!live[k]) deadBytes++;
console.log(`data segment ${segLen} bytes at ${base}: ${deadBytes} unreachable`);
const runs = [];
for (let k = 0; k < segLen; ) {
  if (!live[k]) { k++; continue; }
  let e = k; while (e < segLen && live[e]) e++;
  runs.push({ oldAddr: base + k, bytes: seg.subarray(k, e) });
  k = e;
}

// --- compact the live runs and rewrite the constants that name them ---
const move = new Map();
let a = base;
for (const r of runs) { move.set(r.oldAddr, a); a += r.bytes.length; }
const raw = Buffer.concat(runs.map((r) => r.bytes));
sec(11).payload = Buffer.concat([uleb(1), uleb(0), Buffer.from([0x41]), uleb(base), Buffer.from([0x0b]), uleb(raw.length), raw]);
for (const c of cs) {
  if (!move.has(c.v)) continue;
  const na = uleb(move.get(c.v));
  if (na.length !== c.end - c.at - 1) throw new Error(`LEB width changed for ${c.v}`);
  na.copy(code, c.at + 1);
}

// --- drop the heap bracket, its scratch local, and the heap global ---
let k = 0;
const rdc = () => { let v = 0, sh = 0, c; do { c = code[k++]; v |= (c & 0x7f) << sh; sh += 7; } while (c & 0x80); return v; };
const nf = rdc();
const bodies = [];
for (let f = 0; f < nf; f++) { const size = rdc(); bodies.push(Buffer.from(code.subarray(k, k + size))); k += size; }
const head = Buffer.from([0x01, 0x01, 0x7f, 0x23, 0x00, 0x21, 0x00]);
const tail = Buffer.from([0x20, 0x00, 0x24, 0x00, 0x0b]);
let brackets = 0;
const keepBracket = process.env.KEEP_BRACKET === "1";
for (let f = 0; f < bodies.length; f++) {
  const y = bodies[f];
  if (!keepBracket && y.length > 12 && y.subarray(0, 7).equals(head) && y.subarray(y.length - 5).equals(tail)) {
    bodies[f] = Buffer.concat([Buffer.from([0x00]), y.subarray(7, y.length - 5), Buffer.from([0x0b])]);
    brackets++;
  }
}
console.log(`heap-mark brackets removed: ${brackets}`);
sec(10).payload = Buffer.concat([uleb(bodies.length), ...bodies.flatMap((x) => [uleb(x.length), x])]);
const touchesHeap = bodies.some((x) => x.includes(Buffer.from([0x23, 0x00])) || x.includes(Buffer.from([0x24, 0x00])));
if (brackets > 0 && !touchesHeap) {
  const g = secs.findIndex((s) => s.id === 6);
  if (g >= 0) { secs.splice(g, 1); console.log('global section removed (nothing reads or writes the heap pointer)'); }
}

const out = [Buffer.from(b.subarray(0, 8))];
for (const s of secs) out.push(Buffer.from([s.id]), uleb(s.payload.length), s.payload);
const res = Buffer.concat(out);
writeFileSync(process.argv[3], res);
console.log(`${b.length} -> ${res.length} bytes (-${b.length - res.length})`);
