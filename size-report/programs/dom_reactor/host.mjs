// The page half of dom_reactor: stubs for the four DOM imports, then the four
// calls a page makes. wasmtime cannot run this module -- its imports come from
// the host page's `env` module -- so this is what checks it before it is
// measured.
//
//   node size-report/programs/dom_reactor/host.mjs dom.wasm
//
// Prints `OK 8 host calls` when the observed transcript is exactly the expected
// one, and `FAIL ...` with the first difference otherwise. The transcript is
// pinned in full, strings included: a module that loses a literal, reads the
// wrong length, or folds a branch away still instantiates and still returns the
// right integers, and only the text catches it.
import { readFileSync } from 'node:fs';

const dec = new TextDecoder('utf-8', { ignoreBOM: true });
let mem = null;
const seen = [];
const str = (ptr, len) => dec.decode(new Uint8Array(mem.buffer, ptr, len));

const env = {
	js_log: (p, l) => seen.push(['log', str(p, l)]),
	js_set_text: (p1, l1, p2, l2) => seen.push(['set_text', str(p1, l1), str(p2, l2)]),
	js_append_text: (p1, l1, p2, l2) => seen.push(['append_text', str(p1, l1), str(p2, l2)]),
	js_set_badge_color: (p1, l1, p2, l2) => seen.push(['set_badge_color', str(p1, l1), str(p2, l2)])
};

const { instance } = await WebAssembly.instantiate(readFileSync(process.argv[2]), { env });
const e = instance.exports;
mem = e.memory;

// A reactor has no _start; a wasm-GC build still gets an _initialize if it has
// one, and a --no-gc build has nothing to run before the first export call.
if (typeof e._initialize === 'function') {
	e._initialize();
}

e.InitApp();
const sum = e.AddNumbers(1234, 5678);
const fib20 = e.RunComputation(20);
e.AppendLogMessage(1);
e.AppendLogMessage(2);
e.AppendLogMessage(3);

const expected = [
	['log', 'Reactor core initialized and is ready to serve.'],
	['set_text', 'status-panel', 'Running (module live)'],
	['set_badge_color', 'status-panel', '#2f7f4f'],
	['set_text', 'main-output',
		'The compiled reactor module is online and idle.\nPress a button to trigger native computation.'],
	['log', 'Entering run-computation inside this module...'],
	['append_text', 'event-logbox',
		'\n[event] Button A pressed: linear memory layout validated ok.'],
	['append_text', 'event-logbox',
		'\n[event] Button B pressed: slice buffer manipulation has completed fine.'],
	['append_text', 'event-logbox',
		'\n[event] Heartbeat tick received from the web page.']
];

const problems = [];
if (sum !== 6912) {
	problems.push(`AddNumbers(1234,5678) = ${sum}, expected 6912`);
}
if (fib20 !== 6765) {
	problems.push(`RunComputation(20) = ${fib20}, expected 6765`);
}
if (seen.length !== expected.length) {
	problems.push(`${seen.length} host calls, expected ${expected.length}`);
}
for (let i = 0; i < Math.min(seen.length, expected.length); i++) {
	if (JSON.stringify(seen[i]) !== JSON.stringify(expected[i])) {
		problems.push(`call ${i}: ${JSON.stringify(seen[i])} != ${JSON.stringify(expected[i])}`);
		break;
	}
}

if (problems.length === 0) {
	console.log(`OK ${seen.length} host calls`);
} else {
	console.log(`FAIL ${problems[0]}`);
	process.exitCode = 1;
}
