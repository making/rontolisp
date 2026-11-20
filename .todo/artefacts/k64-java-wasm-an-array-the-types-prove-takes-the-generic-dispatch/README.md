# k64: an array the types prove, measured 2026-11-20

What was learned is in `.kb/java-frontend.md`, "Numbers", `.kb/declarations-type-checks.md`,
"Declaration-driven array emission", and `.kb/wasm-int-fusion.md`, "Ranges, and sites with
nothing to bail on". Here: the programs and how they were run (base `303deba92`).

- `measure.sh <exec jar> <out dir>`, from the repository's root: the module sizes of `Zone`,
  `SieveLoop`, `LongSum`, `ZoneLoop` and YAVI's `Booking.java` / `Member.java`, and the
  functions of the zone reader (`-Drontolisp.wasm.debug-func-sizes`).
- Times: `wasmtime compile` each module, then `/usr/bin/time -f %U wasmtime run
  --allow-precompiled`, base and new interleaved, the best of two to four on a 64-core box
  other sessions loaded (load average 5-65). `SieveLoop` is a sieve of 50,000,000, bound by
  memory; `LongSum` sums a `long[1000]` 10^5 times through `a[i] * 31 + (a[i] >>> 7)`;
  `ZoneLoop` asks Europe/Paris for 2,000,000 offsets of an instant and of a local date-time.
- `lenarm.lisp`: 10^8 lengths of a general vector in a loop, declared `(simple-array t (*))`
  (the inline arm) against undeclared (`_seq_len`), printed in ms.
- The general moves of `.todo/artefacts/j63-starlark-under-cpython/general-move.lisp`, and the
  same with the `(simple-array t (*))` declaration deleted.
