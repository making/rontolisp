# WASM serve: the whole top-level program runs on EVERY request

Difficulty: Medium

A served WASI component is far slower per request than the interpreter or the JVM
(4018 rps / 0.99ms vs 18880 / 0.42ms and 21781 / 0.37ms on the same trivial handler),
and the reason is not the handler: it is that `wasmtime serve` instantiates the
component per request, so `_start` -- the entire top-level program -- runs again for
every request.

Independent of `.todo/258` (the Clack-native `http-handler` cutover); that change
neither causes nor fixes this.

## The evidence

`examples/asdf/clack-hello.lisp`-shaped Clack component under
`wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y`,
benchmarked for 10s (about 35700 requests). The server log contains:

```
stdout [312] :: Rontolisp server is going to start.
stdout [313] :: Rontolisp server is going to start.
```

235 occurrences of clackup's startup banner across 314 distinct instance ids -- one
per request. `.kb/fetch-http.md` documents the mechanism: a serve component never lifts
`run`, so the `handle` wrapper calls `_start` under a serve-only `(mut i32)` init flag
(`serveInitGlobalIndex`); when the host re-instantiates per request that flag is fresh
every time, so init is per request by construction.

Corroborating: the 1.87 MB Clack component and the 867 KB native one serve at almost
the same rate (3567 vs 3664 rps). If per-request WORK dominated, the Clack one -- which
re-runs `clackup` plus every `defvar` in the spliced clack/lack tree -- would be far
slower. The cost is instantiation + init, not the handler.

## Levers, in expected-payoff order

1. **Host-side instance reuse -- no rontolisp change, the biggest effect.**
   `wasmtime serve` is 1 request per instance. Spin reuses an instance for up to 128
   requests by default (`--max-instance-reuse-count`, recorded in `.kb/fetch-http.md`
   and `.kb/tcp-sockets.md`'s three-host instance-lifetime comparison), and wasmCloud
   differs again. **The measurement above is therefore the worst case.** Re-measure on
   Spin and wasmCloud before optimizing anything, and record the three numbers -- if
   the production host reuses instances, most of what follows is not worth doing.
   Also check wasmtime's pooling allocator options for `serve`.

2. **Skip (or shrink) the 16 MiB GC-heap pre-grow in serve mode.** `_start` pre-grows
   the engine's GC heap with one dropped 16 MiB allocation (`.kb/wasm-gc-heap-pregrow.md`,
   `WasmGcHeapPregrowTest`) because wasmtime's copying collector otherwise never grows
   past ~2x the live set. That is a LONG-LIVED-instance optimization; on an instance
   that serves exactly one request it is pure per-request waste. Make it serve-aware
   (skip it, or size it from a much smaller constant) and measure both ways. Keep the
   non-serve path byte-identical, and keep the pin test honest -- under a host that
   DOES reuse instances (Spin) the pre-grow is still wanted, so this may have to be a
   flag rather than an unconditional removal. Write the reason for whichever way it
   goes into the `.kb` file as a re-evaluation trigger.

3. **Cut the per-request init work itself.** Everything at top level runs per request,
   so:
   - measure `--optimize` (tree shaking) on a serve component -- currently unmeasured;
   - `LibraryDefunPruner` coverage for the serve-side splices;
   - do not splice machinery the program cannot use (the concrete instance found in
     `.todo/258`: the CLOS Gray stream + UTF-8 encoder are spliced into every serve
     component even when nothing asks for a buffered `:raw-body`, +35% module size on
     the native path);
   - for Clack specifically, `clackup` prints its banner and the debug-mode NOTICE on
     every request -- that is real I/O per request; consider passing `:silent`-style
     options or suppressing it in the shim.

4. **Module size as a proxy.** Instantiation cost scales with what has to be
   initialized, so size reductions from (3) should show up directly in the per-request
   number. Track module size alongside rps in every measurement.

## How to measure

`/tmp/.../scratchpad/bench/Bench.java` from the 2026-08-04 session (a closed-loop
driver: N connections, fixed duration, rps + latency percentiles) -- re-create it if
the scratchpad is gone; it is about 100 lines over `java.net.http.HttpClient`.

```
rontolisp app.lisp -o app.wasm --component
wasmtime serve -W gc=y -W exceptions=y --addr 127.0.0.1:8091 app.wasm
java Bench.java http://127.0.0.1:8091/hello 4 10
```

Baselines to beat (wasmtime 47.0.2, 4 connections, 10s, trivial handler):

| case | rps | mean | module |
|---|---|---|---|
| native http-handler | 4018.4 | 0.99ms | 641392 B |
| clack | 3617.9 | 1.10ms | 1717524 B |

Useful comparison points from the same session and machine: the same handler on the
interpreter is 18880 rps / 0.42ms and on the JVM 21781 rps / 0.37ms.

## Suggested first step

Measure the same component on Spin (canary, wasmtime 47) and on `wash dev`, then
decide. If a reusing host already lands near the JVM number, this todo is mostly a
documentation change -- say so in `doc/{en,ja}/guides/http-handler.md`, which currently
tells the reader nothing about per-request instantiation cost, and in
`.kb/fetch-http.md` next to the existing instance-lifetime notes.
