// Drives every export of a probe module across boundary values and prints, per
// call, either the value that came back (and what a sink import received) or
// the trap. Usage: node trap.mjs probe.wasm
import { readFileSync } from 'node:fs';

const bytes = readFileSync(process.argv[2]);
let received = [];
let u64Source = 0n;
const env = {};
for (const t of ['s8', 's16', 's32', 'u8', 'u16', 'u32']) {
	env['sink_' + t] = (v) => received.push(v);
}
env.sink_u64 = (v) => received.push(v);
env.src_u64 = () => u64Source;
const { instance } = await WebAssembly.instantiate(bytes, { env });
const e = instance.exports;

const B = (n) => 2n ** BigInt(n);
const ranges = {
	s8: [-B(7), B(7) - 1n, 8],
	s16: [-B(15), B(15) - 1n, 16],
	s32: [-B(31), B(31) - 1n, 32],
	u8: [0n, B(8) - 1n, 8],
	u16: [0n, B(16) - 1n, 16],
	u32: [0n, B(32) - 1n, 32],
	u64: [0n, B(64) - 1n, 64],
};
function values(t) {
	const [min, max, bits] = ranges[t];
	const w = B(bits);
	const set = new Set([min, max, min - 1n, max + 1n, 0n, 1n, -1n, min - w, max + w, w, -w, B(63) - 1n, -B(63), 2n, min + 1n, max - 1n]);
	// The house integer is a signed i64: clamp the set to what the caller can pass.
	return [...set].filter((v) => v >= -B(63) && v < B(63)).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
}
function call(name, arg) {
	received = [];
	try {
		const r = e[name](arg);
		return `ok result=${typeof r === 'bigint' ? r + 'n' : r} received=[${received.map(String).join(',')}]`;
	}
	catch (err) {
		return `TRAP ${err.constructor.name}: ${err.message}`;
	}
}
const names = Object.keys(e).filter((n) => typeof e[n] === 'function').sort();
for (const name of names) {
	if (name === 'get_u64') {
		for (const v of [0n, 1n, B(63) - 1n, B(63), B(64) - 1n]) {
			u64Source = v;
			console.log(`${name}(src=${v}) -> ${call(name)}`);
		}
		continue;
	}
	const t = name.split('_')[1];
	for (const v of values(t)) {
		console.log(`${name}(${v}) -> ${call(name, v)}`);
	}
}
