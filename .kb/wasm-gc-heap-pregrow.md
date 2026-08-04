# `_start` pre-grows the engine's GC heap with one dropped allocation

**Invariant**: the first thing the emitted `_start` body does (both Preview 1 and the
component core; NOT `--no-gc`, which has no GC heap) is allocate and immediately drop
a `TYPE_STR_BYTES` byte array — `GC_HEAP_PREGROW_BYTES` (16 MiB) normally,
`GC_HEAP_PREGROW_SERVE_BYTES` (1 MiB) in **serve** mode. Pinned by
`WasmGcHeapPregrowTest` (both sizes, and that a serve core carries only the serve one);
the emission site is Pass 2b in `WasmLispCompiler.compile`.

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

**Cost, non-serve: none measurable.** The array is garbage before user code runs;
steady-state and peak RSS of a hello-world module are unchanged (~35 MB either way --
the pages of an untouched default-zero byte array are never committed), and V8
discards the transient allocation with a minor GC. 16 MiB is ~4x the live set of the
largest library stack shipped today (cl-postgres + deps, low single-digit MB); if
stacks grow past that, raise the constant -- the sweep in todo-188 showed the benefit
plateaus once headroom clears the live set by ~2x.

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
