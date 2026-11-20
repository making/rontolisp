# k68: a quotient of declared integers, measured 2026-11-20

What was learned is in `.kb/wasm-int-fusion.md`, "Quotients of two integers". Base `b089a691d`.

- Sizes: `.todo/artefacts/k64-java-wasm-an-array-the-types-prove-takes-the-generic-dispatch/measure.sh`
  with each exec jar.
- `DivLoop.java`: 30,000,000 `int` and `long` `/` over computed divisors. `wasmtime compile` the
  module, then `/usr/bin/time -f %U wasmtime run --allow-precompiled`, base and new interleaved,
  best of three.
