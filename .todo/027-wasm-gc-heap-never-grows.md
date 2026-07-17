# 27 - WASM GC backend: linear-memory string heap (OOB on large allocation / never freed)

## STATUS: core issue RESOLVED. Only 2 bounded single-read I/O bump sites remain unguarded.

The GC backend kept string BYTES in a shared linear bump heap at `HEAP_PTR_ADDR`
(cons/closures/symbols-as-structs are GC objects, not linear). Two problems:
**(a)** it never grew, so a program allocating past the initial memory trapped
"memory access out of bounds"; **(b)** it never freed, so it grew without bound
(a resident reactor building strings across calls -- the *cumulative* symptom,
reproduced by `examples/browser/rainbow/rainbow.lisp`'s O(n^2) concatenate).

Both are now fixed:

- **(a) grow-on-demand** (2026-06-29): `WasmEmitHelper.emitGrowHeapTo(w, pushTop)`
  -- a pure-stack "grow if it doesn't cover `top`" guard (no new local/function, so
  the `FUNC_*` indices + component blobs stay valid). Emitted at the string
  builders (`emitBuildCore`, `buildToStringBody`, `buildWriteStrBody` capture,
  `buildStrFromMemBody`, `__ronto_alloc`, `_str_to_mem`, `_intern` pool copy).
- **(b) no-more-leak** (landed as commit `6c32b12`, 2026-07-07): string bytes moved onto the wasm-GC
  heap (`$str_bytes` arrays); `HEAP_PTR` is now a STACK pointer -- runtime string
  builds assemble in a reused scratch and POP it (id = a monotonic counter, not the
  offset). So string building no longer advances `HEAP_PTR`. Verified bounded/steady
  linear memory across N=200000 builds. Details: `.kb/wasm-gc-strings.md`.

## Remaining (low priority): 2 unguarded single-allocation bump sites

A SINGLE value larger than the current linear size can still OOB at these two
sites, which assemble bytes inline WITHOUT an `emitGrowHeapTo` guard (both bounded
single I/O reads, unlikely to exceed memory in one call):

- `WasmGetenvRuntimeBuilder.build` -- the getenv value copy (0 guards).
- `WasmRuntimeBuilder.buildReadLineBody` -- the `fd_read` byte-at-a-time line assembly.

(The fetch response string used to be a third site. `WasmFetchRuntimeBuilder` was
deleted in commit `00aef99`, and the current component fetch path --
`WasmComponentImportCompiler.emitStageStringParam` -- IS guarded via
`emitGrowHeapTo`.)

Fix: add `emitGrowHeapTo` at each bump (trivial; the string builders + `_str_to_mem`
are the pattern; `NoGcWasmCompiler.allocBody` is the `--no-gc` reference). Then
re-verify all four backends + native + the component path (byte-sensitive).

Related: `.kb/wasm-gc-strings.md` (the leak fix + representation).
