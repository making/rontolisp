// node runwasi.mjs module.wasm "<runtime string>"
// Instantiates a --no-gc WASI module with node:wasi for fd_write and an env host
// that prints each host call to fd 1 synchronously (same channel as fd_write,
// so ordering matches the interpreter's).
import { readFileSync, writeSync } from 'node:fs';
import { WASI } from 'node:wasi';
const wasi = new WASI({ version: 'preview1', args: [], env: {} });
const bytes = readFileSync(process.argv[2]);
let mem = null;
const dec = new TextDecoder('utf-8', { fatal: true });
const str = (p, l) => dec.decode(new Uint8Array(mem.buffer, p, l));
const say = (s) => writeSync(1, s + '\n');
const env = {
	h2: (p1, l1, p2, l2) => say('[h2 ' + str(p1, l1) + '|' + str(p2, l2) + ']'),
	h3: (p1, l1, n, p2, l2) => say('[h3 ' + str(p1, l1) + '|' + n + '|' + str(p2, l2) + ']'),
};
for (const tag of ['h1', 'ha', 'hb', 'hc', 'hd', 'he', 'hf', 'hg', 'hh', 'hi']) {
	env[tag] = (p, l) => say('[' + tag + ' ' + str(p, l) + ']');
}
const { instance } = await WebAssembly.instantiate(bytes, { ...wasi.getImportObject(), env });
mem = instance.exports.memory;
wasi.initialize(instance);
const arg = new TextEncoder().encode(process.argv[3] ?? '');
const p = instance.exports.__ronto_alloc(arg.length);
new Uint8Array(mem.buffer).set(arg, p);
instance.exports.RunAll(p, arg.length);
