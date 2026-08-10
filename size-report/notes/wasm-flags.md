## What is measured

[`programs/`](../programs) holds the two micro programs, which exist only to be
measured:

| Directory | Program |
| --- | --- |
| [`programs/hello_world/`](../programs/hello_world) | Write `Hello, World!` to stdout, and nothing else |
| [`programs/pi_approx/`](../programs/pi_approx) | Approximate pi with the Leibniz series, 1,000,000 terms, to 15 decimal places |

Each has a `-nogc` companion, because `--no-gc` accepts only `(defun ...)` and
`rontolisp:wasm-export` at top level and has no `format`. They follow
[wado-lang/wado `wasm-size/`](https://github.com/wado-lang/wado/tree/main/wasm-size),
a cross-language Wasm size comparison, so the rows can be read next to C, Rust,
Zig, Moonbit and Wado.

Every module is checked before it is measured: it must still print the right
answer under `wasmtime`, or the run fails instead of reporting a smaller number
for a module that stopped working.

## Reading the numbers

**`Module` and `WASI` are read back out of the artifact**, not restated from the
flags, so a row cannot keep claiming a shape the backend has stopped emitting.
`core` is a plain wasm module and `component` a WASI component (the header
distinguishes them); `command` means the module starts itself -- `_start`, or an
exported `wasi:cli/run` -- while `reactor` means the host calls a named export
instead, which is what `--no-gc` emits (`say-hello`, `approx-pi`). A component
still carries a Preview 1 adapter inside it; the `WASI` column reports what the
artifact imports from the outside, which for a component is `0.3.0` -- the same
thing the cross-language table below calls Preview 3.

**`--optimize` is not optional.** Without it a module carries the whole prelude
-- ~124 KB for `hello_world`, 99.6% of which nothing in the program reaches.
`--optimize` is the dead-code tree-shaker; it is what turns 124 KB into a few
hundred bytes. Only tree-shaken numbers are worth comparing.

**wasm-GC modules are not like-for-like with C or Zig.** rontolisp's default
WASM backend is wasm-GC: strings and objects are host-managed GC types, so the
module ships no allocator, no `malloc` and no linear-memory bookkeeping, while
every Preview 1 row from a linear-memory language carries its own heap. The
comparable rows are the `--no-gc` ones, which emit a plain MVP core module.

**`--component` costs about 1.1 KB.** It re-frames the module as a WASI 0.3
component; the canonical-ABI adapters and the type section are the whole
difference. Its imports are then stated as a WIT world rather than as
`fd_write`.

## Cross-language context

Quoted from the upstream README (measured there on 2026-08-03 with wasi-sdk
25.0, rustc 1.97.1, Zig 0.15.2, Moonbit 0.1.20260803) -- **not** re-measured
here, so read it as context rather than as a controlled benchmark. Each language
is built with its own size-optimization flags. The rontolisp rows are the table
above.

### hello_world

| Language | WASI | Size (bytes) |
| --- | --- | ---: |
| wado | Preview 3 (component) | 1,974 |
| c | Preview 1 | 3,829 |
| zig | Preview 1 | 4,449 |
| moonbit | Preview 1 | 9,227 |
| rust | Preview 1 | 40,365 |

### pi_approx

| Language | WASI | Size (bytes) |
| --- | --- | ---: |
| wado | Preview 3 (component) | 6,034 |
| zig | Preview 1 | 10,608 |
| c | Preview 1 | 18,105 |
| moonbit | Preview 1 | 22,986 |
| rust | Preview 1 | 59,753 |

## Flags

| Flag | What it does |
| --- | --- |
| `--optimize` | Dead-code-eliminate: keep only what `_start` and the exports reach |
| `--optimize=size` | The above, plus trade speed for size -- drops fused integer trees and unboxed locals. Costs up to 6x the runtime on integer-heavy code |
| `--component` | Emit a WASI 0.3 component instead of a Preview 1 module |
| `--no-gc` | Emit a plain MVP core module: no wasm-GC, numeric subset only, exports rather than `_start` |
| `--no-wasi` | No WASI imports at all; a reactor with `_initialize` instead of `_start` |
