# k76: an array's making and store, measured 2026-11-21

What was learned is in `.kb/java-frontend.md`, "Numbers", `.kb/array-literals.md`, "What a
wasm `make-array` site costs", and `.kb/declarations-type-checks.md`, "Declaration-driven
array emission". Base `57598eafd`.

- Module and function sizes: `../k64-java-wasm-an-array-the-types-prove-takes-the-generic-dispatch/measure.sh`,
  and `Sieve.java` of `../j68-java-front-end-spike/programs/`.
- `Arr.java`: one method per array kind; its methods' sizes with
  `-Drontolisp.wasm.debug-func-sizes=true`.
- `LongFill.java` / `IntFill.java`: 10^8 stores into a `long[1000]` / an `int[1000]`.
- Times: `wasmtime compile`, then `/usr/bin/time -f %U wasmtime run --allow-precompiled`,
  base and new interleaved, best of three on a loaded 64-core box.
