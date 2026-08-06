# Get a print-only `--component` under 2 KB

Difficulty: Medium

A core module that only writes a constant is now ~645 B (`.kb/optimize-dead-code-elimination.md`,
"the print family's literal fold"). The same program as a WASI 0.3 component is
**2,147 B** -- the wrapper is now more than twice the program. Target: under 2,000 B,
and preferably near 1,500.

## Where the 2,147 bytes are

`wasm-tools objdump` on `(print "Hello World!")` at `--optimize`, 2026-08-06:

| part | bytes | what |
| --- | --- | --- |
| core module 2 | 653 | the compiled program (287 code + 170 data + 80 types) |
| core module 1 | 577 | the preview1 adapter: 11 imports, 6 functions, 294 B code |
| core module 0 | 158 | memory + `cabi_realloc` (86 B of code) |
| component glue | ~760 | component types 208, core instances 221, aliases 123, canonical functions 38, imports 77, exports 24 |

## Three concrete candidates

### 1. `wasi:cli/stderr` is imported unconditionally (~130-150 B)

Measured: `(print "x")` and `(format *error-output* "x~%")` import the SAME four
interfaces -- `wasi:cli/run`, `wasi:cli/stdout`, `wasi:cli/stderr`, `wasi:cli/types`.
A program with no `*error-output*` write, no `warn`, no `error` and no condition
report still carries the stderr interface type (69 B), its import (26 B), its
alias, its `write-via-stream` lowering and the adapter's `stderr-write` shim.

The likely reason (VERIFY before acting): the adapter exports ONE `fd_write` that
dispatches on the descriptor, so both `stdout-write` and `stderr-write` are
reachable from it and the surface gate todo-270 built cannot separate them.
Splitting the shim per descriptor -- or specializing it when the program provably
writes only fd 1 -- would let stderr drop out whole. Note `.kb/standard-output-redirect.md`:
stderr is the RESERVED handle 2, so "does this program write handle 2" is a
question the compiler can answer from the source.

### 2. `cabi_realloc` and its module (158 B)

Core module 0 exists to export `memory` + `cabi_realloc`. Check whether a
component whose lowered imports never need a realloc (only `list<u8>` writes out,
nothing lifted in) can declare the canonical options without it, or share the
core module's own memory instead of a separate module.

### 3. The adapter's async path (part of the 577 B)

The adapter imports `future-read-cli`, `future-drop-cli`, `waitable-set-new`,
`waitable-join`, `waitable-set-wait` -- WASI 0.3 streams are asynchronous, so one
blocking write still builds a future and a waitable set. Establish whether 0.3
offers a shorter blocking spelling for a write that is immediately awaited; if not,
record that as the floor and say so, so the next visitor does not re-derive it.

## Also relevant

`.todo/271` (the pinned `StringTable` prologue, ~104 B) is inside core module 2
here, so it counts against this budget too.

## How to verify

The component must still run: `wasmtime run -W gc=y hello.wasm` (wasmtime 46+),
and the `--component` legs of `CiSpecE2eTest` must stay green -- a dropped import
that the program CAN reach is a runtime trap, not a compile error, so the
reachability question above has to be answered from the source, not by trying it.
Add a size pin next to `WasmTreeShakerTest.everySpellingOfHelloWorldReachesTheSameFloor`.

## Non-goals

- The core module's own floor beyond `.todo/271`.
- Preview 1 output, which is already at its floor.
