// usage: node bytes.mjs mod.wasm -- drives the four exports with a runtime :string
// through the arena, and prints one row per input for diffing against the interpreter.
import { readFileSync } from 'node:fs';
const m = await WebAssembly.instantiate(readFileSync(process.argv[2]), {});
const e = m.instance.exports, mem = e.memory;
const put = s => { const b = Buffer.from(s, 'utf8'); const p = e.__ronto_alloc(b.length); Buffer.from(mem.buffer).set(b, p); return [p, b.length]; };
for (const s of ['abc', '日本語', 'aé日']) {
  console.log(`(${e.SLen(...put(s))} ${e.C0(...put(s))} ${e.C1(...put(s))} ${e.SubTail(...put(s))})`);
}
