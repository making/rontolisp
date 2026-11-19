# k19. wasm: a resume body spills every live local at every suspend site

Difficulty: High

Measured by k07 (2026-11-19, `.kb/java-frontend.md`, "What k07 measured and made of the
resumable lowering";
`.todo/artefacts/k07-java-front-end-what-the-suspended-threads-slice-left/spill/sites.py`
writes the programs): a suspend site of a resume body (`WasmAsyncEmit`) costs 250 B and
12 B for each local alive across it, and some 42 B once for each such local -- 1 await 329 B;
11 awaits 2,839 / 4,615 / 6,405 B at 0 / 10 / 20 locals; 21 awaits 5,357 / 8,353 /
11,363 B. So a body's bytes grow with awaits x locals. The Java corpus's `Handing`: its
`main` is one resume body of 93,005 B with 82 awaits and some 74 locals alive at each,
about 73 KB of it spills; the module is 961,876 B, 377,598 B of it 154 resume bodies
(k00 measured 417 KB on one stack against 712 KB resumable for the program as it was
then).

Leads, none measured:

- A local alive across an await lives in the frame's spill array and is read and written
  there: no spill at a site, no restore in the prologue. An access costs more bytes and
  time than a `local.get` -- a resume body is the slow path already (no fusion, no
  unboxed number) -- so measure both on `Handing` and on the corpus's timings before
  choosing; a middle way is a local in the frame only when it is alive across more than
  so many sites.
- A function awaited in tail position needs no frame of its own beyond what
  `JavaResumable.forwards` finds: a `synchronized` region's body ending in a wait is not
  a tail today.
- The 250 B of a site with nothing alive: what of it is the state dispatch, the future's
  registration, the handler state (`WasmFutureRuntimeBuilder.HandlerState`).

Every `async-defun` of a component pays the same, not the Java front end alone:
`-Drontolisp.wasm.debug-func-sizes` on the served examples first.

## Done when

The lead is made, or measured and decided with the numbers in `.kb/async-await.md`.
