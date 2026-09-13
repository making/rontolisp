import { readFileSync } from 'node:fs';
const NAMES = { 1: 'type', 2: 'import', 3: 'func', 5: 'memory', 6: 'global', 7: 'export', 10: 'code', 11: 'data' };
for (const f of process.argv.slice(2)) {
	const b = readFileSync(f);
	let i = 8;
	const rows = [];
	let dataPayload = 0, codeFuncs = [];
	while (i < b.length) {
		const id = b[i++];
		let size = 0, shift = 0, byte;
		do { byte = b[i++]; size |= (byte & 0x7f) << shift; shift += 7; } while (byte & 0x80);
		const start = i;
		rows.push([NAMES[id] ?? ('id' + id), size]);
		if (id === 11) {
			// count, flag, i32.const off, end, len, bytes
			let j = start;
			const rd = () => { let v = 0, s = 0, c; do { c = b[j++]; v |= (c & 0x7f) << s; s += 7; } while (c & 0x80); return v; };
			rd(); rd(); rd(); j++; /* 0x0b end */ dataPayload = rd();
		}
		if (id === 10) {
			let j = start;
			const rd = () => { let v = 0, s = 0, c; do { c = b[j++]; v |= (c & 0x7f) << s; s += 7; } while (c & 0x80); return v; };
			const n = rd();
			for (let k = 0; k < n; k++) { const sz = rd(); codeFuncs.push(sz); j += sz; }
		}
		i = start + size;
	}
	console.log(f, 'total=' + b.length);
	console.log('  ' + rows.map(([n, s]) => n + '=' + s).join(' '));
	console.log('  dataPayload=' + dataPayload);
	console.log('  funcBodySizes=[' + codeFuncs.join(',') + '] sum=' + codeFuncs.reduce((a, c) => a + c, 0));
}
