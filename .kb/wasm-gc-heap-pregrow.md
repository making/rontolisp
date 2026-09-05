# `_start` pre-grows the engine's GC heap with one dropped allocation

**Invariant**: the first thing the emitted `_start` body does (Preview 1 and the
component core; NOT `--no-gc`) is allocate and immediately drop a `TYPE_STR_BYTES` byte
array. Size follows the program (`WasmLispCompiler.gcHeapPregrowBytes`):
`GC_HEAP_PREGROW_CODE_FACTOR` (16) x emitted user-function bytes, clamped between
`GC_HEAP_PREGROW_BYTES` (16 MiB floor) and `GC_HEAP_PREGROW_MAX_BYTES` (64 MiB) — except
**serve** mode, always `GC_HEAP_PREGROW_SERVE_BYTES` (1 MiB). Emitted at Pass 2b in
`WasmLispCompiler.compile`, pinned by `WasmGcHeapPregrowTest`.

**The size is a CORRECTNESS matter on wasmtime 47** — lowering the floor, ceiling or
factor is a correctness change, not a performance knob, until the collector bug below is
fixed.

- Why: wasmtime's copying collector grows only when a SINGLE allocation cannot fit in the
  space a collection frees (`collect_and_maybe_grow_gc_heap`,
  `crates/wasmtime/src/runtime/store/gc.rs`). The heap never shrinks, so one large
  transient allocation permanently buys headroom; the array is garbage before user code
  runs, so RSS is unchanged.
- Not one constant: the live set follows what the program LOADS. 16 MiB covers
  cl-postgres alone, not `rove` on top; on cl-postgres + rove (3.3 MB of emitted defuns)
  26.5 MiB still collects and 32 MiB does not, so factor 16 gives a ~2x margin.

## Sibling knob: the LINEAR memory's declared minimum
`WasmLispCompiler.memoryMinPages`. Rule: **static data plus a heap at least as large as
it** (`HEAP_HEADROOM_MIN_PAGES` = 3 floor). The old fixed ~192 KB exhausted mid-load on
cl-unicode, trapping `out of bounds memory access` with an unnamed backtrace. Both
emission sites take it — the Preview 1 / `--no-wasi` memory section and the component's
`mem` import minimum (which drives `WasmComponentBuilder.memModuleFor`). The bump sites
are unguarded, so it must be right up front. Pinned by `WasmLinearMemoryHeadroomTest`.

## The copying collector loses a reference with no headroom
On **wasmtime 47.0.3**, with too little headroom a boxed local's cell reads back as
another cell and the next use traps uncatchably (`wasm trap: cast failure`), no condition
any handler can see. Always lands during a NON-LOCAL EXIT, so adding one form anywhere
hides it. Green under `-C collector=drc` and `-O gc-heap-initial-size=33554432`; traps
under the default `-C collector=copying` — that pair of runs is the whole diagnosis. Not
the heap moving, not Cranelift. Not the module's fault: wasm-GC references cannot be
stored anywhere the collector does not trace.

## Why serve is different
`_start` runs **once per INSTANCE**, and a served component is instantiated many times
(`wasmtime serve --max-instance-reuse-count`, 128 default; Spin inherits it, wasmCloud
`wash dev` uses 1 — `.kb/tcp-sockets.md`). Growth costs ~**1.5 ms per MiB** on wasmtime
47, so in serve mode the pre-grow is request latency. 1 MiB is the compromise: optimal at
the reuse count every real host uses (+30% native / +27% clack over 16 MiB), ~2% mean
throughput on a never-retired instance; dropping it entirely is worse except at reuse=1.
wasmCloud pools the heap mapping, so the reuse=1 column bounds the SHAPE of the cost, not
its size — measure the host. The correctness caveat applies here too: nothing measured
has hit the bug, but if one does, weigh `-C collector=drc` on the host rather than raising
the constant.
