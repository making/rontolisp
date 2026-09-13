// Prints the byte size of every function body in the code section of a wasm module.
import fs from 'node:fs';
const b = fs.readFileSync(process.argv[2]);
let p = 8;
function leb() { let r = 0, s = 0; for (;;) { const x = b[p++]; r |= (x & 0x7f) << s; if (!(x & 0x80)) return r; s += 7; } }
let names = [];
const sections = [];
while (p < b.length) {
  const id = b[p++]; const size = leb(); const start = p;
  sections.push({ id, size, start });
  if (id === 7) { // exports
    const n = leb();
    for (let i = 0; i < n; i++) { const l = leb(); const name = b.subarray(p, p + l).toString(); p += l; const kind = b[p++]; const idx = leb(); if (kind === 0) names[idx] = name; }
  }
  p = start + size;
}
const code = sections.find(s => s.id === 10);
const imports = sections.find(s => s.id === 2);
let nImports = 0;
if (imports) { p = imports.start; nImports = leb(); }
p = code.start;
const n = leb();
let total = 0;
const rows = [];
for (let i = 0; i < n; i++) {
  const sz = leb(); const bodyStart = p; p += sz;
  const sizeBytes = bodyStart - (p - sz - (bodyStart - (p - sz)));
  const idx = nImports + i;
  rows.push(`${String(idx).padStart(3)}  ${String(sz).padStart(4)}  ${names[idx] ?? ''}`);
  total += sz;
}
console.log(`code section ${code.size} bytes, ${n} functions, bodies ${total} bytes`);
console.log(rows.join('\n'));
