// Per-function body size (and hex) of a core module's code section, with the
// export name where one names the function. Usage: node fnsizes.mjs a.wasm [b.wasm]
import { readFileSync } from 'node:fs';
function parse(file) {
	const b = readFileSync(file);
	let i = 8;
	const rd = () => { let v = 0, s = 0, c; do { c = b[i++]; v |= (c & 0x7f) << s; s += 7; } while (c & 0x80); return v; };
	let nImports = 0;
	const exports = {};
	const bodies = [];
	while (i < b.length) {
		const id = b[i++];
		const size = rd();
		const end = i + size;
		if (id === 2) {
			const n = rd();
			for (let k = 0; k < n; k++) {
				const ml = rd(); i += ml; const nl = rd(); i += nl;
				const kind = b[i++];
				if (kind === 0) { rd(); nImports++; }
				else if (kind === 1) { i++; const f = rd(); if (f & 1) rd(); rd(); }
				else if (kind === 2) { const f = rd(); rd(); if (f & 1) rd(); }
				else if (kind === 3) { i += 2; }
			}
		}
		else if (id === 7) {
			const n = rd();
			for (let k = 0; k < n; k++) {
				const nl = rd(); const name = Buffer.from(b.subarray(i, i + nl)).toString(); i += nl;
				const kind = b[i++]; const idx = rd();
				if (kind === 0) exports[idx] = name;
			}
		}
		else if (id === 10) {
			const n = rd();
			for (let k = 0; k < n; k++) {
				const sz = rd();
				bodies.push({ idx: nImports + k, size: sz, hex: Buffer.from(b.subarray(i, i + sz)).toString('hex') });
				i += sz;
			}
		}
		i = end;
	}
	return bodies.map((f) => ({ ...f, name: exports[f.idx] ?? '' }));
}
const [a, c] = process.argv.slice(2);
const A = parse(a);
if (!c) {
	for (const f of A) console.log(`${f.idx}\t${f.name.padEnd(10)}\t${f.size}\t${f.hex}`);
}
else {
	const C = parse(c);
	const byName = Object.fromEntries(C.filter((f) => f.name).map((f) => [f.name, f]));
	for (const f of A) {
		if (!f.name) continue;
		const g = byName[f.name];
		console.log(`${f.name.padEnd(10)} before=${f.size} after=${g ? g.size : '?'} delta=${g ? g.size - f.size : '?'}`);
		console.log(`   before ${f.hex}`);
		if (g) console.log(`   after  ${g.hex}`);
	}
}
