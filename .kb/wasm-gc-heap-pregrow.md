# `_start` pre-grows the engine's GC heap with one dropped 16 MiB allocation

**Invariant**: the first thing the emitted `_start` body does (both Preview 1 and the
component core; NOT `--no-gc`, which has no GC heap) is allocate and immediately drop
a `GC_HEAP_PREGROW_BYTES` (16 MiB) `TYPE_STR_BYTES` byte array. Pinned by
`WasmGcHeapPregrowTest`; the emission site is Pass 2b in `WasmLispCompiler.compile`.

**Why**: wasmtime's copying (semispace) collector grows the heap only when a SINGLE
allocation cannot fit in the space a collection frees
(`collect_and_maybe_grow_gc_heap` in `crates/wasmtime/src/runtime/store/gc.rs`, plus
the grow-or-collect heuristic that grows only once the live set passes half the
capacity). A Lisp program's long-lived environment -- symbols, function wrappers,
library data built at load time -- therefore ends up occupying a large share of a
barely-grown heap, and every hot loop's boxing allocations trigger a collection
every few hundred KB, each one copying the ENTIRE live set. That is the todo-188
"module-size tax": 1200 never-called defuns (really: their live registration data)
made a pure arithmetic loop 1.6x slower and PBKDF2 2x slower, and quickloading the
cl-postgres stack made the component leg 20.8 s instead of ~3 s. The heap never
shrinks, so one large transient allocation at startup permanently buys the headroom
the incremental path never asks for.

**Cost**: none measurable. The array is garbage before user code runs; steady-state
and peak RSS of a hello-world module are unchanged (~35 MB either way -- the pages
of an untouched default-zero byte array are never committed), and V8 discards the
transient allocation with a minor GC. 16 MiB is ~4x the live set of the largest
library stack shipped today (cl-postgres + deps, low single-digit MB); if stacks
grow past that, raise the constant -- the sweep in todo-188 showed the benefit
plateaus once headroom clears the live set by ~2x.

**Re-evaluation trigger**: this compensates for wasmtime's heap-growth policy as of
47.x. If wasmtime gains live-ratio-based growth (or a generational collector), the
prologue becomes harmless but pointless and can be retired; re-test by comparing the
todo-188 PBKDF2 benchmark with and without the prologue under the then-current
wasmtime.
