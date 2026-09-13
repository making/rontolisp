import { readFileSync } from 'node:fs';

const file = process.argv[2];
const bytes = readFileSync(file);
let mem = null;
const dec = new TextDecoder('utf-8', { ignoreBOM: true });
const out = [];
function str(ptr, len) {
	return dec.decode(new Uint8Array(mem.buffer, ptr, len));
}
const env = {
	js_log: (p, l) => out.push(['log', str(p, l)]),
	js_set_text: (p1, l1, p2, l2) => out.push(['set_text', str(p1, l1), str(p2, l2)]),
	js_append_text: (p1, l1, p2, l2) => out.push(['append_text', str(p1, l1), str(p2, l2)]),
	js_set_badge_color: (p1, l1, p2, l2) => out.push(['set_badge_color', str(p1, l1), str(p2, l2)])
};
const { instance } = await WebAssembly.instantiate(bytes, { env });
mem = instance.exports.memory;
const e = instance.exports;
e.InitApp();
out.push(['AddNumbers', e.AddNumbers(1234, 5678)]);
out.push(['RunComputation', e.RunComputation(20)]);
e.AppendLogMessage(1);
e.AppendLogMessage(2);
e.AppendLogMessage(3);
console.log(JSON.stringify(out, null, 1));
