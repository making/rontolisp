# k84: a general array's store and `new T[n][m]`, measured 2026-11-21

What was learned is in `.kb/java-frontend.md`, "Numbers", and
`.kb/declarations-type-checks.md`, "Declaration-driven array emission". Base `46d8ae6c2`.

- `LongFill.java` / `IntFill.java` (k76's artefact) and `RefFill.java`: 10^8 stores into a
  `long[1000]`, an `int[1000]`, a `String[1000]`.
- `Grid.java`: 10^6 `new int[4][4]`; `GridHand.java` the same rows made by a loop the program
  writes. On the JVM `Grid` with 10^7 rounds, on the interpreter with 10^5.
- Sizes: `../k64-java-wasm-an-array-the-types-prove-takes-the-generic-dispatch/measure.sh`.
- Times: `wasmtime compile`, then `/usr/bin/time -f %U wasmtime run --allow-precompiled`,
  base and new interleaved, best of three.
- Tried first: `%java-new-rows` with its size declared `(integer 0 2147483647)`, the library
  kept: the function 1,605 -> 1,413 B, `Grid` 0.39-0.45 -> 0.35-0.38 s, against 0.21-0.28 for
  the loop by hand. Lowered at the site instead.
