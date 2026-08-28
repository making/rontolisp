# `_start` pre-grows the engine's GC heap with one dropped allocation

**Invariant**: the first thing the emitted `_start` body does (both Preview 1 and the
component core; NOT `--no-gc`, which has no GC heap) is allocate and immediately drop
a `TYPE_STR_BYTES` byte array. Its size **follows the program**
(`WasmLispCompiler.gcHeapPregrowBytes`): `GC_HEAP_PREGROW_CODE_FACTOR` (16) times the
emitted user-function bytes, clamped between `GC_HEAP_PREGROW_BYTES` (16 MiB, the floor
a library-free program keeps) and `GC_HEAP_PREGROW_MAX_BYTES` (64 MiB) — except in
**serve** mode, which always pre-grows `GC_HEAP_PREGROW_SERVE_BYTES` (1 MiB). Pinned by
`WasmGcHeapPregrowTest` (the floor, the serve size, the clamp formula, and that a
program carrying a stack's worth of code does NOT pre-grow only the floor); the emission
site is Pass 2b in `WasmLispCompiler.compile`.

**The size is a CORRECTNESS matter on wasmtime 47, not only a performance one** — see
"the copying collector loses a reference" below before lowering any of these numbers.

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

**Cost, non-serve: none measurable at the floor.** The array is garbage before user
code runs; steady-state and peak RSS of a hello-world module are unchanged (~35 MB
either way -- the pages of an untouched default-zero byte array are never committed),
and V8 discards the transient allocation with a minor GC. 16 MiB is ~4x the live set of
a program that loads no library stack; the sweep in todo-188 showed the benefit plateaus
once headroom clears the live set by ~2x. **Why the size is no longer a single
constant**: the live set is not a property of the compiler, it is a property of what the
program LOADS -- interned symbols, function wrappers, CLOS/defstruct metaobjects, class
and dispatch tables all scale with the amount of code spliced in. 16 MiB covered
cl-postgres alone (low single-digit MB); `rove` on top of it does not fit, and a
program that pre-grows too little pays far more than slowness (below). Scaling off the
emitted user code keeps a small program at exactly the old floor -- which matters for
memory-capped hosts (a Cloudflare Worker reactor gets the floor, not 64 MiB) -- while a
library stack gets headroom proportional to what it loaded. Measured on the biggest
stack here (cl-postgres + rove, 3.3 MB of emitted defuns): a 26.5 MiB heap still
collects, 32 MiB does not, i.e. ~9x the emitted code; the factor is 16 for the same ~2x
margin the plateau above wants.

## The sibling knob: the LINEAR memory's declared minimum (todo-545)

Different memory, same shape of mistake. Above the static data and the runtime
intern region sits the linear bump heap, and `WasmLispCompiler.memoryMinPages`
is what asks the host for it. The rule used to be "static data rounded up to a
page, **plus three**, floored at four" — a fixed ~192 KB of heap no matter how
big the program is. What the bump heap holds is one identity per
runtime-created string, so its need follows what a program BUILDS at load time,
and cl-unicode (68,000 character names plus 11,172 computed Hangul ones)
exhausts 192 KB before it finishes loading, trapping `out of bounds memory
access` with nothing to go on but the address — which is exactly the memory
size, in a five-frame backtrace of unnamed functions.

The rule is now **the static data plus a heap at least as large as it**
(`HEAP_HEADROOM_MIN_PAGES` = 3 is the floor a program with almost no static data
keeps, so nothing small moves). Measured on `(ql:quickload "str")` plus one
`str:title-case`: 76 pages of static data, 18 to 32 pages of heap actually
needed, 76 given. Both emission sites take it — the Preview 1 / `--no-wasi`
memory section and the component's `mem` import minimum, which is what tells
`WasmComponentBuilder.memModuleFor` to grow the shared mem module too. Declaring
more pages costs nothing at rest (untouched pages are never committed), which is
why the answer is a bigger minimum rather than a bigger constant floor: a
memory-capped host still gets the small program's four pages. Pinned by
`WasmLinearMemoryHeadroomTest`.

The unguarded bump sites `.todo/027` still names are the reason this has to be
right up front rather than grown on demand at every writer.

## The copying collector loses a reference when the heap has no headroom (todo-409)

On **wasmtime 47.0.3** the pre-grow is what keeps a large `--component` program
CORRECT, not merely fast. With too little headroom the default **copying** collector
loses a live GC reference: a boxed local's cell reads back as *another cell* rather than
its value, so the next use of it traps uncatchably -- `close` on the stale value fails
its `ref.cast (ref i31)` and the run dies with `wasm trap: cast failure`, with no
condition any handler can see.

What it took to establish that, and how to re-establish it if it comes back:

- The reproduction is `.todo/408`'s cl-postgres-client rove suite compiled
  `--component`. Symptom: 166 assertions pass, then the raw trap.
- **The same module is green under `-C collector=drc`** (183 passed / 2 failed at the
  time, the handler-case-shadowing pair, fixed since) and green under
  `-O gc-heap-initial-size=33554432`; it traps under
  `-C collector=copying` (the default) with the stock heap. That pair of runs is the
  whole diagnosis: same bytes, same program, collector-dependent behavior.
- It is not the heap moving in the host address space (`-O gc-heap-may-move=n` still
  traps) and not a Cranelift optimization (`-O opt-level=0` still traps).
- It is not something the emitted module can be doing wrong: wasm-GC references cannot
  be stored anywhere the collector does not trace (locals, globals, tables and GC
  objects are all traced, and linear memory cannot hold a reference at all), so a
  reference that survives a collection stale is the engine's to fix.
- The failure always lands during a NON-LOCAL EXIT -- the value read wrong is a boxed
  local of a frame whose `unwind-protect` cleanup is running while an exception is in
  flight -- which is why it looks like a language bug and why it is so
  layout-sensitive: whether a collection happens to land inside that window depends on
  the allocation history, so adding one form anywhere in the program hides it.

**Re-evaluation trigger**: when wasmtime fixes the copying collector (or rontolisp pins
a version where the two runs above agree), the sizing goes back to being a pure
performance knob and the factor can be re-tuned on the todo-188 benchmark alone. Until
then, treat lowering the floor, the ceiling or the factor as a correctness change.

## Why serve is different (todo-259)

`_start` runs **once per INSTANCE**, and a served component is instantiated many
times over one server run: `wasmtime serve` retires an instance after
`--max-instance-reuse-count` requests (128 by default for a WASIp3 component; Spin
inherits the same default, wasmCloud `wash dev` uses 1 -- `.kb/tcp-sockets.md` has
the three-host comparison). So in serve mode the pre-grow is not a startup cost
amortized over a process lifetime, it is **request latency**, and it is far from
free: growth is linear in the size at roughly **1.5 ms per MiB** on wasmtime 47
(first-touch page faults on the new semispace dominate), i.e. **25 ms for 16 MiB**.

Measured 2026-08-04, wasmtime 47.0.2, trivial handler, 4 connections x 10 s closed
loop, `rps` (`Bench.java`); "native" = `rontolisp:http-handler` directly, "clack" =
`ql:quickload "clack"` + `clackup`:

| pre-grow | native @ reuse=1 | native @ reuse=128 | clack @ reuse=128 | clack @ reuse=inf |
|---|---|---|---|---|
| 0        | 3676 | 4905 | 4491 | 4194 |
| 256 KiB  | -    | 4928 | 4733 | 4650 |
| **1 MiB**| 1708 | **5025** | **4719** | 4736 |
| 4 MiB    |  617 | 4409 | 4235 | -    |
| 16 MiB   |  154 | 3810 | 3702 | 4832 |

**The correctness caveat above applies here too, and serve does not buy its way out
of it**: a served component keeps the 1 MiB per-instance pre-grow whatever it loads, so
a handler carrying a stack the size of cl-postgres + rove is exposed to the wasmtime 47
copying-collector bug that the process-lifetime sizing avoids. Nothing measured has hit
it yet (a served handler's live set is the same load-time environment, but its requests
allocate far less than a test suite does between collections); if one does, the answer
is not to raise the serve constant blindly -- re-run the sweep below with the handler's
own stack, and weigh it against `-C collector=drc` on the host.

1 MiB is the compromise the code ships: at the reuse count every real host uses it
is the optimum (+30% native / +27% clack over 16 MiB), and on an instance that is
NEVER retired it costs ~2% mean throughput and a slightly fatter tail (p99 1.6 ms vs
1.2 ms -- more frequent, cheaper collections). Dropping the pre-grow entirely is
worse than 1 MiB everywhere except reuse=1, so serve keeps a pre-grow rather than
skipping it.

**Confirmed on Spin** (canary 4.1.0-pre0, same driver, same day): its instance
reuse is the same 128 (counter 1..128 then reset), and 16 MiB -> 1 MiB is
+8%/+19% at 4 connections and +18%/+29% at 16 (native/clack). Spin's own
per-request overhead is larger, so the pre-grow is a smaller share of the whole --
but the TAIL improvement is bigger there than anywhere: p99 10.4 ms -> 3.9 ms
(native, 16 conns), 10.1 ms -> 4.2 ms (clack). **wasmCloud is where it matters most**
(wash 2.6.1, one FRESH instance per request -- counter reads 1 every time):

| conns | native before | native after | gain |
|---|---|---|---|
| 1 | 110.0 | 710.2 | 6.5x |
| 4 | 281.7 / 285.8 (repeats) | 1809.9 / 1586.6 | ~6x |
| 8 | 514.4 | 4338.6 | 8.4x |
| 16 | 867.2 | 7883.3 | **9.1x** |

clack there: 258.7 -> 1033.5 (4 conns), 814.8 -> 5955.2 (16 conns). Before the
change the per-request mean is 9-19 ms and the host never clears 900 rps whatever
the concurrency; after it, mean drops to 1.4-2.7 ms and it scales like the others.
wasmCloud is NOT simply the `--max-instance-reuse-count 1` column above -- 282 rps
at 4 connections against wasmtime's 154 says it pools or reuses the heap mapping
across instances, so the reuse=1 column bounds the SHAPE of the cost, not its
size. Measure the host, do not extrapolate from the knob.

**Re-evaluation triggers**, two independent ones:

1. This compensates for wasmtime's heap-growth policy as of 47.x. If wasmtime gains
   live-ratio-based growth (or a generational collector), the prologue becomes
   harmless but pointless and can be retired; re-test by comparing the todo-188
   PBKDF2 benchmark with and without the prologue under the then-current wasmtime.
2. The serve constant is tuned to a host that retires an instance every ~128
   requests. If the hosts move to long-lived (or pooled-and-reset) instances, the
   serve size should converge back on the process-lifetime constant; re-run the sweep
   above with `--max-instance-reuse-count` at 1 / 128 / a very large number. A
   handler whose own live set is much larger than the trivial one measured here also
   wants a bigger constant -- the table's shape, not its exact rps, is what
   transfers.
