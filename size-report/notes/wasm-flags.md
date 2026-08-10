## What is measured

[`programs/`](../programs) holds the programs, which exist only to be measured:

| Directory | Program |
| --- | --- |
| [`programs/hello_world/`](../programs/hello_world) | Write `Hello, World!` to stdout, and nothing else |
| [`programs/pi_approx/`](../programs/pi_approx) | Approximate pi with the Leibniz series, 1,000,000 terms, to 15 decimal places |
| [`programs/zlib/`](../programs/zlib) | gunzip a 64 KB stream with [chipz](https://github.com/froydnj/chipz) 0.8, `ql:quickload`ed from the live Quicklisp dist like the Worker family (so the row tracks whatever version the dist serves; the pinned copy `ChipzE2eTest` runs against is `src/test/resources/chipz/`) |

The two micro programs have a `-nogc` companion each, because `--no-gc` accepts
only `(defun ...)` and `rontolisp:wasm-export` at top level and has no `format`.
`zlib` has none: that backend has no arrays at all, so a deflate library cannot
be expressed there. They follow
[wado-lang/wado `wasm-size/`](https://github.com/wado-lang/wado/tree/main/wasm-size),
a cross-language Wasm size comparison, so the rows can be read next to C, Rust,
Zig, Moonbit and Wado.

`zlib` does the same WORK as its upstream namesake -- gzip in, decompressed
bytes out -- but it is not yet the same program, and the difference is worth
stating before the numbers are read:

| | upstream (c / rust / zig / wado) | rontolisp |
| --- | --- | --- |
| input | read all of stdin | a 507-byte literal embedded in the source |
| output | write the decompressed bytes to stdout | print the length and an FNV-1a |

Both differences are the same missing feature: rontolisp has no binary
stdin/stdout (`read-byte`/`write-byte` need a stream from `open`). What the
summary line costs was measured rather than guessed -- replacing it with a bare
`(princ (length raw))` saves 2,008 bytes of 432,134, because chipz's own
condition reports already pull the format renderer in -- so it is the missing
I/O, not the reporting, that keeps the row from being like-for-like.

What the row does measure is the decompressor: an inflate state machine over
typed bit buffers, the Huffman tables, a 32 KB window and CRC32, all compiled in
from a third-party library's unmodified sources. The stream inflates to 65536
octets (a 512-byte block repeated 128 times, so both the literal path and long
back-references through the window run) and the FNV-1a is what pins every
backend to the same bytes.

**`zlib` is the row that carries the runtime, not just the library.** chipz
calls `apply`, which turns the embedded `eval` runtime on, and it uses
`catch`/`throw`, which puts the module in EH mode (hence `-W exceptions=y` in
the run, and why the four rows are ~430 KB rather than the ~100 KB the inflate
code alone would be). Both are properties of the library's own source, not of
how the program was written.

Every module is checked before it is measured: it must still print the right
answer under `wasmtime`, or the run fails instead of reporting a smaller number
for a module that stopped working.

## Reading the numbers

**`Module` and `WASI` are read out of the artifact**, not restated from the
flags. `core` vs `component` is the wasm header; `command` starts itself
(`_start`, or an exported `wasi:cli/run`), `reactor` waits for the host to call a
named export, which is what `--no-gc` emits. A component embeds a Preview 1
adapter; the column reports what it imports from outside, not that.

**One name per WASI generation.** Preview 1 carries no version -- it is the
import module `wasi_snapshot_preview1`; later generations version each interface
(`wasi:cli/stdout@0.3.0`). The tables fold those back: WASI 0.3 is Preview 3.

**`--optimize` is not optional.** Without it a module carries the whole prelude
-- ~124 KB for `hello_world`, 99.6% of which nothing in the program reaches.
`--optimize` is the dead-code tree-shaker; it is what turns 124 KB into a few
hundred bytes. Only tree-shaken numbers are worth comparing.

**`--optimize=size` only shows up on a big program.** On both micro programs it
measures the same as plain `--optimize` -- there is nothing left to trade once
the tree-shaker has run. On `zlib` it is another 23% (558,907 -> 432,134),
because there the fused integer trees and unboxed locals it drops are spread
over a whole library rather than a dozen forms.

**The unoptimized micro rows are the prelude, so they move when the prelude
does.** They grew by ~2.3 KB when `fill` joined it; `--optimize` takes both back
to the same bytes as before, which is the point of only comparing tree-shaken
numbers.

**wasm-GC modules are not like-for-like with C or Zig.** rontolisp's default
WASM backend is wasm-GC: strings and objects are host-managed GC types, so the
module ships no allocator, no `malloc` and no linear-memory bookkeeping, while
every Preview 1 row from a linear-memory language carries its own heap. The
comparable rows are the `--no-gc` ones, which emit a plain MVP core module.

**`--component` costs about 1.1 KB.** It re-frames the module as a Preview 3
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

### zlib

Same task in every row -- gzip decompress -- but not yet the same program: see
the input/output table above, and note that the rontolisp row is a dynamic
language's whole runtime plus a library that pulls in `eval` and exceptions,
where the others are a decompressor and nothing else.

| Language | WASI | Size (bytes) | What it does |
| --- | --- | ---: | --- |
| wado | Preview 3 (component) | 16,237 | stdin + gzip decompress (`core:zlib`) |
| zig | Preview 1 | 20,072 | stdin + gzip decompress (`std.compress`) |
| c | Preview 1 | 34,484 | stdin + gzip decompress (zlib 1.3.1) |
| rust | Preview 1 | 89,069 | stdin + gzip decompress (zlib-rs) |

## Flags

| Flag | What it does |
| --- | --- |
| `--optimize` | Dead-code-eliminate: keep only what `_start` and the exports reach |
| `--optimize=size` | The above, plus trade speed for size -- drops fused integer trees and unboxed locals. Costs up to 6x the runtime on integer-heavy code |
| `--component` | Emit a Preview 3 component instead of a Preview 1 module |
| `--no-gc` | Emit a plain MVP core module: no wasm-GC, numeric subset only, exports rather than `_start` |
| `--no-wasi` | No WASI imports at all; a reactor with `_initialize` instead of `_start` |
