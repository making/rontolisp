# i69. What a wasm-GC Proxy-Wasm filter runs per exchange, and how much of it the SDK can drop

Difficulty: Medium

`sluice-client` interprets plugins with Endive, so an exchange's time follows its instruction count. i51 made the SDK small;
this is what it runs. The aim is to keep what wasm-GC gives a filter (`defclass`, `handler-case`, `car`) and find how far the
count per exchange can fall, for example without CLOS dispatch. CLOS is **a suspect, not a finding**: nothing has measured
its share. Start from the per-function breakdown.

## Measured 2026-11-05 by the sluice session

develop `93877c2e1`, `--optimize=size`, on a JVM. Instruction counts do not move with load. Times are warm p50 at load 36-44.
A plugin that does nothing is 13 instructions and 0.40-0.44ms.

| Filter | Rust | wasm-GC | `--no-gc` |
| --- | ---: | ---: | ---: |
| `http_body`, 5-byte body: instructions | 2,342 | 13,967 | 475 |
| Same: function calls | 28 | 441 | 25 |
| Same: time | 0.46-0.71ms | 0.87-1.15ms | 0.38-0.57ms |
| `http_headers`: instructions | 28,124 | 118,699 | 10,997 |
| Same: function calls | 465 | 3,039 | 248 |
| Same: time | 1.4-1.7ms | 4.3-9.3ms | 0.8-1.4ms |
| `http_body`, 64KiB body: instructions | 462,207 | 3,227,624 | 5,636,321 |
| Same: function calls | 37 | 553 | 196,630 |

GC instructions are 11-15% of the count (4% on the 64KiB body), so they do not look like the main cause. That is a share of
instructions: allocation and the JVM's collection time were not measured.

The count comes from an `ExecutionListener` that tallies each `Instruction`'s opcode, installed in `ProxyWasmFilter.start()`.

## What to find out

1. Where `http_body`'s 441 calls and 13,967 instructions go: dispatch, `handler-case`, the context table, and so on.
2. How far a filter falls without CLOS, for example a `define-filter` on the wasm-GC SDK that expands to direct functions, as
   `examples/proxy-wasm/no-gc/` does. Say how per-stream state is held.
3. `--no-gc`'s 64KiB body makes 3 calls per byte and is slower than wasm-GC. `search-bytes` is a candidate.

## A decision for the user

Whether the `defmethod` API stays or is replaced. sluice's `proxy-wasm-plugins.md` and `src/test/guest/rontolisp/lisp_plugin.lisp`
use it (`proxy-wasm:on-response-headers` and so on). This item proposes; the answer is the user's. If the API changes, tell the
sluice session.

## Done when

The breakdown and the numbers are in `.kb/proxy-wasm-sdk.md`, and the choice about the API is in front of the user with what each
option costs.
