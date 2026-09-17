# 839: identity-keyed hash tables on WASM -- the measurements

The numbers live in `.kb/hash-tables.md` ("The identity-hash slot") and
`.kb/wasm-gc-object-size.md`; this directory keeps what produced them.

## Fill + lookup of an eq table keyed by n conses (`ht-cons.lisp`)

Replace `SIZE` with 1000 / 10000 / 100000 and run on each backend
(`.kb/running-backends.md`). Before the identity-hash slot (2026-09-17, wasmtime 47.0.3):

| n | interpreter fill/lookup ms | JVM | WASM Preview 1 | WASM component |
| --- | --- | --- | --- | --- |
| 10^3 | 111 / 22 | 2 / 1 | 8 / 6 | 9 / 8 |
| 10^4 | 159 / 71 | 9 / 6 | 542 / 482 | 547 / 490 |
| 10^5 | 480 / 267 | 54 / 26 | 50,909 / 63,226 | 64,330 / 75,679 |

After: WASM Preview 1 0/0, 2/1, 96/18; component 1/0, 3/1, 110/18.

## 300,000 identity keys in one table (`agg-keys.lisp`)

The program the pinning tests run, with a timer. After the slot: interpreter 4,523 ms,
JVM 3,544 ms, WASM Preview 1 1,447 ms, component 1,425 ms, Preview 1 `--simd` 1,504 ms;
all print `(250000 100000 1000000 40)`. Before the slot the WASM runs did not finish
inside the test runner's 300 s.

## Bytes per struct on the GC heap

`consN.wat` / `cellN.wat` allocate n structs of one shape into a list. `probe.sh
<module.wat> <n>...` asks wasmtime whether n fit in a hard-capped 64 MiB heap;
`node measure-v8.mjs <module.wasm> <n>` (after `wasm-tools parse x.wat -o x.wasm`)
prints V8's used-heap delta per object.

| shape | wasmtime 47.0.3 | V8 13 (node 24.19) |
| --- | --- | --- |
| `{eqref}` | 32 | 24 |
| `{eqref, i32}` | 32 | 32 |
| `{eqref, eqref}` | 32 | 32 |
| `{eqref, eqref, i32}` | 32 | 40 |
| `{eqref, eqref, i32, i32}` | 32 | 40 |
| `{eqref, eqref, i32 x4}` | 48 | 48 |
