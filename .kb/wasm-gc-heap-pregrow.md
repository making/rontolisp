# `_start` pre-grows the engine's GC heap with one dropped allocation

**Invariant**: the first thing the emitted `_start` body does (Preview 1 and the
component core; NOT `--no-gc`, which has no GC heap) is allocate and immediately drop a
`TYPE_STR_BYTES` byte array. Size follows the program
(`WasmLispCompiler.gcHeapPregrowBytes`): `GC_HEAP_PREGROW_CODE_FACTOR` (16) x emitted
user-function bytes, clamped between `GC_HEAP_PREGROW_BYTES` (16 MiB floor) and
`GC_HEAP_PREGROW_MAX_BYTES` (64 MiB) — except **serve** mode, always
`GC_HEAP_PREGROW_SERVE_BYTES` (1 MiB). Emitted at Pass 2b in `WasmLispCompiler.compile`.
Pinned by `WasmGcHeapPregrowTest`.

**The size is a CORRECTNESS matter on wasmtime 47** — read the collector section before
lowering any of these numbers.

Why: wasmtime's copying (semispace) collector grows only when a SINGLE allocation cannot
fit in the space a collection frees (`collect_and_maybe_grow_gc_heap`,
`crates/wasmtime/src/runtime/store/gc.rs`), so a Lisp program's load-time environment
occupies most of a barely-grown heap and every hot loop's boxing collects every few
hundred KB, copying the whole live set. The heap never shrinks, so one large transient
allocation permanently buys headroom; the array is garbage before user code runs, so RSS
is unchanged (untouched zero pages are never committed).

Not one constant, because the live set follows what the program LOADS (interned symbols,
function wrappers, CLOS/defstruct metaobjects, dispatch tables). 16 MiB covers
cl-postgres alone; `rove` on top does not fit. Scaling off emitted code keeps a small
program at the floor (a Cloudflare Worker reactor gets 16 MiB, not 64). On cl-postgres +
rove (3.3 MB of emitted defuns) a 26.5 MiB heap still collects and 32 MiB does not (~9x
the emitted code); factor 16 gives a ~2x margin.

## Sibling knob: the LINEAR memory's declared minimum

`WasmLispCompiler.memoryMinPages` sizes the linear bump heap above the static data and
intern region. The old rule (static data rounded to a page, plus three, floored at four)
gave a fixed ~192 KB whatever the program size. The bump heap holds one identity per
runtime-created string, so it follows what a program BUILDS at load time; cl-unicode
(68,000 character names + 11,172 computed Hangul) exhausts 192 KB mid-load, trapping
`out of bounds memory access` with nothing but the address in a five-frame backtrace of
unnamed functions.

Rule now: **static data plus a heap at least as large as it**
(`HEAP_HEADROOM_MIN_PAGES` = 3 is the floor). Both emission sites take it — the Preview 1
/ `--no-wasi` memory section and the component's `mem` import minimum, which is what
tells `WasmComponentBuilder.memModuleFor` to grow the shared mem module. Extra pages cost
nothing at rest. Pinned by `WasmLinearMemoryHeadroomTest`. The unguarded bump sites are
why this must be right up front rather than grown on demand.

## The copying collector loses a reference when the heap has no headroom

On **wasmtime 47.0.3** the pre-grow keeps a large `--component` program CORRECT. With too
little headroom the default copying collector loses a live GC reference: a boxed local's
cell reads back as *another cell*, so the next use traps uncatchably — `close` on the
stale value fails its `ref.cast (ref i31)` and the run dies with `wasm trap: cast
failure`, no condition any handler can see.

Re-establishing it:

- Reproduction: a cl-postgres-client rove suite compiled `--component`; 166 assertions
  pass, then the raw trap.
- **Green under `-C collector=drc`** and under `-O gc-heap-initial-size=33554432`; traps
  under `-C collector=copying` (the default) with the stock heap. Same bytes, same
  program — that pair of runs is the whole diagnosis.
- Not the heap moving (`-O gc-heap-may-move=n` still traps), not Cranelift
  (`-O opt-level=0` still traps).
- Not the module's fault: wasm-GC references cannot be stored anywhere the collector does
  not trace, so a stale surviving reference is the engine's to fix.
- Always lands during a NON-LOCAL EXIT — the wrong value is a boxed local of a frame
  whose `unwind-protect` cleanup runs while an exception is in flight — which is why
  adding one form anywhere hides it.

**Trigger**: when wasmtime fixes the copying collector (or rontolisp pins a version where
the two runs agree), sizing is a pure performance knob again. Until then, lowering the
floor, ceiling or factor is a correctness change.

## Why serve is different

`_start` runs **once per INSTANCE**, and a served component is instantiated many times:
`wasmtime serve` retires an instance after `--max-instance-reuse-count` requests (128 by
default; Spin inherits it, wasmCloud `wash dev` uses 1 — `.kb/tcp-sockets.md`). So in
serve mode the pre-grow is request latency, and growth is ~**1.5 ms per MiB** on
wasmtime 47 (first-touch page faults), i.e. 25 ms for 16 MiB.

1 MiB is the shipped compromise: optimal at the reuse count every real host uses (+30%
native / +27% clack over 16 MiB), costing ~2% mean throughput and a slightly fatter tail
on a never-retired instance. Dropping the pre-grow entirely is worse than 1 MiB except at
reuse=1. Confirmed on Spin (biggest tail gain, p99 10.4 ms -> 3.9 ms) and wasmCloud (one
FRESH instance per request: 6-9x throughput, mean 9-19 ms -> 1.4-2.7 ms). wasmCloud is
NOT simply the reuse=1 column — it pools or reuses the heap mapping, so that column bounds
the SHAPE of the cost, not its size. Measure the host.

**The correctness caveat applies here too and serve does not buy out of it**: a served
component keeps 1 MiB per instance whatever it loads. Nothing measured has hit the bug (a
served handler's requests allocate far less between collections than a test suite); if
one does, re-run the sweep with the handler's own stack and weigh `-C collector=drc` on
the host rather than raising the constant blindly.

**Triggers**: (1) if wasmtime gains live-ratio-based growth or a generational collector,
the prologue becomes harmless but pointless — retest with the PBKDF2 benchmark with and
without it. (2) If hosts move to long-lived (or pooled-and-reset) instances, the serve
size converges back on the process-lifetime constant; re-run the sweep with
`--max-instance-reuse-count` at 1 / 128 / very large. A handler with a much larger live
set also wants a bigger constant.
