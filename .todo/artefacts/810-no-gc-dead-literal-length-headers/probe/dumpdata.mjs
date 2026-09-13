// node dumpdata.mjs mod.wasm -- prints the whole active data segment as a byte walk,
// marking every 4-byte little-endian run that reads as a plausible [len] header.
import { readFileSync } from 'node:fs';
const b = readFileSync(process.argv[2]);
let i = 8;
while (i < b.length) {
	const id = b[i++];
	let size = 0, sh = 0, by;
	do { by = b[i++]; size |= (by & 0x7f) << sh; sh += 7; } while (by & 0x80);
	const start = i;
	if (id === 11) {
		let j = start;
		const rd = () => { let v = 0, s = 0, c; do { c = b[j++]; v |= (c & 0x7f) << s; s += 7; } while (c & 0x80); return v; };
		const n = rd();
		for (let k = 0; k < n; k++) {
			const flags = rd();
			let off = -1;
			if (flags === 0) { j++; off = rd(); j++; }
			const len = rd();
			const seg = b.subarray(j, j + len);
			console.log(`segment off=${off} len=${len}`);
			console.log(JSON.stringify(Buffer.from(seg).toString('latin1')));
			j += len;
		}
	}
	i = start + size;
}
