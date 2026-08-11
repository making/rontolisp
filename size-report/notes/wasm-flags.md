## What is measured

[`programs/`](../programs) holds the programs, which exist only to be measured:

| Directory | Program |
| --- | --- |
| [`programs/hello_world/`](../programs/hello_world) | Write `Hello, World!` to stdout, and nothing else |
| [`programs/pi_approx/`](../programs/pi_approx) | Approximate pi with the Leibniz series, 1,000,000 terms, to 15 decimal places |
| [`programs/zlib/`](../programs/zlib) | Read gzip data from stdin, decompress it with [chipz](https://github.com/froydnj/chipz) 0.8, write the octets to stdout. chipz is `ql:quickload`ed from the live Quicklisp dist like the Worker family, so the row tracks whatever version the dist serves; the pinned copy `ChipzE2eTest` runs against is `src/test/resources/chipz/` |

The two micro programs have a `-nogc` companion each, because `--no-gc` accepts
only `(defun ...)` and `rontolisp:wasm-export` at top level and has no `format`.
`zlib` has none: that backend has no arrays at all, so a deflate library cannot
be expressed there. They follow
[wado-lang/wado `wasm-size/`](https://github.com/wado-lang/wado/tree/main/wasm-size),
a cross-language Wasm size comparison, so the rows can be read next to C, Rust,
Zig, Moonbit and Wado.

`zlib` is the SAME PROGRAM as its upstream namesake, down to the 8192-byte input
buffer the C and Zig versions use: read all of stdin, inflate, write the
decompressed octets to stdout. Nothing is embedded and nothing is reported, so
the row measures the decompressor and the runtime under it rather than a
literal and a summary line. (It was not always: until binary stdin/stdout
landed, the input was a 507-byte literal compiled into the source and the output
was a length plus an FNV-1a.)

What the row measures is that decompressor: an inflate state machine over typed
bit buffers, the Huffman tables, a 32 KB window and CRC32, all compiled in from
a third-party library's unmodified sources. The check feeds it 65536 octets'
worth of gzip -- 8 distinct 64-byte lines repeated 128 times, so the first block
runs the literal and short-match paths and the rest runs long back-references
through the window -- and requires the output to equal the original byte for
byte.

**`zlib` is the row that carries the runtime, not just the library.** chipz
calls `apply`, which turns the embedded `eval` runtime on, and it uses
`catch`/`throw`, which puts the module in EH mode (hence `-W exceptions=y` in
the run, and why the unoptimized row is hundreds of KB rather than the ~100 KB
the inflate code alone would be). Both are properties of the library's own
source, not of how the program was written.

Every module is checked before it is measured, or the run fails instead of
reporting a smaller number for a module that stopped working: the two micro
programs must still print the right answer under `wasmtime`, and `zlib` -- which
prints nothing -- must gunzip the check stream to exactly the octets it was made
from.

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
the tree-shaker has run. On `zlib` it was another 23% when measured (551,644 ->
425,815, before the dead-branch pruning stages landed), because there the fused
integer trees and unboxed locals it drops are spread over a whole library rather
than a dozen forms.

**The `zlib` rows also carry the dead-branch pruning story.** chipz ships a
whole bzip2 decoder a gzip program never reaches; the AST pruner's dead-branch
stages (`.kb/library-defun-pruning.md`) fold the `case`/`typecase` arms that
anchor it, and the condition-runtime narrowing (`.kb/error-handling.md`) drops
the format renderer and the unreachable seeded-condition arms and layouts. That
is why the `zlib` rows sit far below chipz's full source size while the row's
check still gunzips the stream byte-identically on every backend.

**A constant table now costs its own bytes.** chipz spells every lookup table it
has -- the two 256-entry CRC32 tables, the fixed-block code lengths, the
distance/length codes, ~700 elements in all -- as
`(coerce '(<literals>) '(vector (unsigned-byte 16|32)))`, and that used to be
built cons cell by cons cell at startup, ~11.8 bytes of wasm an element. The
compiler folds the literal call to the specialized vector it produces and the
backend bakes that into the module's static data at the element width
(`.kb/pure-builtin-fold.md`, `.kb/packed-integer-vectors.md`), so the same table
is 4 bytes an element. Worth **-15.6%** on the `--optimize=size` row (191,872 ->
161,976), which is more than the tables' own bytes: a specialized vector also
stops boxing every element, so the general-array and cons paths those tables
pinned shake out with them.

**And a further -1.2% is the data section, not code.** A library whose every slot
accessor is a generic used to ship its name three times over: once for the
`_lookup` registry, once again in the single-colon alias spelling nothing could
address (`.kb/symbol-runtime-api.md`), and once more inside a whole
`"No applicable method: X on "` sentence per generic. Those, plus interning a
layout's print name as a view into its own `%class-` tag rather than a second
copy (`.kb/instance-syntax.md`), are worth 2,235 bytes here -- small next to the
code-side stages above, and the reason the remaining gap to the C and Zig rows
is runtime rather than redundancy. Note what that trade looks like COMPRESSED:
duplicate text is what a compressor collapses for free, so removing it moves the
raw number and barely the gzipped one (the Worker table, which counts gzip,
shows raw down on every row and gzip within a percent either way).

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

Same program in every row -- gzip on stdin, decompressed octets on stdout --
with one thing worth keeping in view: the rontolisp row is a dynamic language's
whole runtime plus a library that pulls in `eval` and exceptions, where the
others are a decompressor and nothing else.

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
