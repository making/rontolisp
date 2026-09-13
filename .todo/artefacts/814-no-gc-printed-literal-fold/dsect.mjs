import { readFileSync } from 'node:fs';
const VT = { 0x7f:'i32', 0x7e:'i64', 0x7d:'f32', 0x7c:'f64' };
for (const f of process.argv.slice(2)) {
  const b = readFileSync(f);
  let i = 8;
  console.log('=== ' + f + ' total=' + b.length);
  while (i < b.length) {
    const id = b[i++];
    let size = 0, sh = 0, by;
    do { by = b[i++]; size |= (by & 0x7f) << sh; sh += 7; } while (by & 0x80);
    const start = i;
    let j = start;
    const rd = () => { let v = 0, s = 0, c; do { c = b[j++]; v |= (c & 0x7f) << s; s += 7; } while (c & 0x80); return v; };
    const hdr = 1 + (size < 128 ? 1 : size < 16384 ? 2 : 3);
    if (id === 11) {
      const n = rd();
      console.log(`  data payload=${size} hdr=${hdr} segments=${n}`);
      let over = 0;
      for (let k = 0; k < n; k++) {
        const s0 = j;
        const flags = rd();
        let off = -1;
        if (flags === 0) { j++; off = rd(); j++; } // i32.const off, end
        const lenAt = j;
        const len = rd();
        const bytes = b.subarray(j, j + len);
        over += (lenAt - s0) + (j - lenAt);
        j += len;
        console.log(`    seg[${k}] flags=${flags} off=${off} len=${len} overhead=${(j - len) - s0} ${JSON.stringify(Buffer.from(bytes).toString('utf8').slice(0, 46))}`);
      }
      console.log(`    -> segment overhead total=${over}  bytes total=${size - over - 1}`);
    } else if (id === 1) {
      const n = rd();
      console.log(`  type payload=${size} hdr=${hdr} count=${n}`);
      for (let k = 0; k < n; k++) {
        const form = b[j++];
        const np = rd(); const ps = []; for (let x = 0; x < np; x++) ps.push(VT[b[j++]] ?? '?' + b[j-1]);
        const nr = rd(); const rs = []; for (let x = 0; x < nr; x++) rs.push(VT[b[j++]] ?? '?' + b[j-1]);
        console.log(`    type[${k}] (${ps.join(' ')}) -> (${rs.join(' ')})`);
      }
    } else if (id === 3) {
      const n = rd(); const t = []; for (let k = 0; k < n; k++) t.push(rd());
      console.log(`  func payload=${size} hdr=${hdr} typeidx=[${t.join(',')}]`);
    } else if (id === 2) {
      const n = rd();
      console.log(`  import payload=${size} hdr=${hdr} count=${n}`);
    } else {
      console.log(`  ${id} payload=${size} hdr=${hdr}`);
    }
    i = start + size;
  }
}
