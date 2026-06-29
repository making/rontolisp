# 27 - WASM GC backend: linear-memory heap never grows (OOB on large allocation)

## STATUS: fixed for string allocation (2026-06-29)

`WasmEmitHelper.emitGrowHeapTo(w, pushTop)` was added: a pure-stack
(no new local, no new function -> all `FUNC_*` indices and the component blobs
stay valid) "grow memory if it does not cover `top`" guard. It is now emitted at
the linear-memory string allocators:

- `WasmExportRuntimeBuilder.buildAllocBody` (`__ronto_alloc`) and
  `buildStrFromMemBody` (`:string` arg boxing).
- `WasmRuntimeBuilder.buildToStringBody` (the open/close quote writes; this body
  backs both `princ-to-string`/`prin1-to-string` AND `concatenate`) and
  `buildWriteStrBody` capture mode (the actual rendered-byte copy).
- `WasmStringRuntimeBuilder.emitBuildCore` (`subseq`/`string-upcase`/`-downcase`/
  `-capitalize`/trim).

Verified: `WasmLispCompilerIntegrationTest#heapGrowsForStringsLargerThanInitialMemory`
builds a ~640 KB string and runs clean under wasmtime; the full WASM/JVM/eval
suites (1235 tests) still pass; interpreter/JVM/WASM output is unchanged (the fix
only enables growth, it does not change any value). Cons cells/lists are GC
structs (not linear memory), so only string bytes use this heap -- the string
sites above cover all of it for compute-only programs.

### Still NOT covered (separate, lower priority)

These also bump `HEAP_PTR_ADDR` inline and were left untouched (each needs the
same guard, but they are I/O paths and far less likely to exceed memory in one
call): `read-line` (the `fd_read` loop in `WasmRuntimeBuilder`),
`WasmReadRuntimeBuilder` (`read`/`read-from-string`), `WasmGetenvRuntimeBuilder`,
`WasmFetchRuntimeBuilder`. Add `emitGrowHeapTo` at their bump sites too for full
coverage.

## Original report

## Symptom

A `--no-wasi` / Preview 1 / default GC module traps with **"memory access out
of bounds"** once a program allocates more than the fixed initial linear memory
(4 pages = 262144 bytes, usable ~245 KB after the reserved low region). It is
not a single-object size limit -- it is *cumulative* heap use within one call,
because linear-memory allocation never frees and never grows.

Reproduced with `examples/rainbow.lisp` (built `--no-wasi`) driven from Node:

```
echo "x".repeat(111)  -> OK   (output 3996 bytes)
echo "x".repeat(112)  -> FAIL memory access out of bounds
```

Isolation (fresh instance each call):
- `echo(s) = s` with a 50000-char input: **OK** (input-side `:string` boxing is fine).
- `blow(n)` building an n*10-byte string by repeated `(concatenate 'string acc "...")`:
  OK at output 1000 bytes, **FAIL** by ~10000 bytes -- the failure tracks
  *cumulative concatenate allocation*, not final size.
- `__ronto_alloc(100000)` returns ptr 16384; `__ronto_alloc(300000)` returns
  566384 with **memory still 4 pages** -- the allocator hands out pointers far
  past the end of memory with no grow and no bounds check.

## Root cause

The GC backend has one shared bump heap at `HEAP_PTR_ADDR` (= 84). Every string
allocation advances it **inline**, with no `memory.grow` and no capacity check:

- `WasmExportRuntimeBuilder.buildAllocBody` (`__ronto_alloc`): loads
  HEAP_PTR_ADDR, stores `old+size` (8-byte aligned), returns `old`. No grow.
- `WasmExportRuntimeBuilder.buildStrFromMemBody` (`:string` arg boxing).
- `WasmStringRuntimeBuilder` (`_string_concat` and friends), the printer
  (`princ-to-string`/`prin1-to-string`), `WasmReadRuntimeBuilder`,
  `WasmGetenvRuntimeBuilder`, `WasmFetchRuntimeBuilder` -- all bump
  `HEAP_PTR_ADDR` directly.

The memory section is `memories.addMemory(4)` (`WasmLispCompiler` ~line 1332),
4 pages, no maximum, never grown at runtime. Contrast `ScalarWasmCompiler`'s
`__alloc`, which DOES `memory.grow` when the bump crosses the current size.

## Fix direction

Make the shared heap grow. Options, roughly increasing in cleanliness/risk:

1. **Grow at each bump site**: after computing the new top, if
   `newTop > memory.size * 65536`, `memory.grow(ceil((newTop - size)/65536))`.
   Touches every builder listed above (hand-assembled byte sequences) but adds
   **no new function index**, so it preserves the `FUNC_*` index-stability /
   component byte-identical-blob invariant (CLAUDE.md). Most surgical re: risk
   to the component path.
2. **Centralize**: add one `_heap_alloc(size) -> ptr` runtime function that
   bumps + grows, and route every inline bump through it. Cleaner, but adding a
   `FUNC_*` shifts indices and would break the fixed-index component blobs unless
   the new function is appended after all index-pinned ones -- verify against
   `WasmComponentBuilder` wiring before doing this.
3. Optionally also raise the initial page count and/or set a memory maximum.

After the fix, re-verify all four backends + the native image + the component
path (the component path is byte-sensitive; confirm blobs/wiring still line up).

## Interaction with the example

`examples/rainbow.lisp` amplifies the problem with an O(n^2) build pattern
(`reduce` with `(concatenate 'string acc next)` copies the whole accumulator
each step) plus a `princ-to-string` per character. Even after the heap grows,
an O(n log n) join (balanced/tree concatenation, or a future `map`+single join)
would cut peak memory and speed it up. But the *correctness* bug is the
non-growing heap; the example pattern only changes where the cliff is.

Related: [[24-wasm-gc-float-mod-rem]], [[25-generic-map-over-sequences]].
The `--no-gc` `ScalarWasmCompiler.allocBody` is the reference for grow-on-bump.
