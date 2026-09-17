// measure-v8.mjs <module.wasm> <n>: bytes per allocated struct on V8 (node), from the
// managed-heap delta of holding n structs in a linked list, GC forced before and after.
import { readFileSync } from "node:fs";
import v8 from "node:v8";
import vm from "node:vm";

v8.setFlagsFromString("--expose-gc");
const gc = vm.runInNewContext("gc");
const [file, nText] = process.argv.slice(2);
const n = Number(nText);
const { instance } = await WebAssembly.instantiate(readFileSync(file), {});
gc();
gc();
const before = v8.getHeapStatistics().used_heap_size;
instance.exports.alloc(n);
gc();
gc();
const after = v8.getHeapStatistics().used_heap_size;
console.log(`${file} n=${n} delta=${after - before} bytes/object=${((after - before) / n).toFixed(2)}`);
