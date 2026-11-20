# k84. Java on wasm: a general array's store and `new T[n][m]`

Difficulty: Medium

Left by k76 (2026-11-21). A packed store takes a raw subscript and an asserted `new T[n]` is a
rank-1 `make-array` (`.kb/declarations-type-checks.md`, "Declaration-driven array emission";
`.kb/array-literals.md`, "What a wasm `make-array` site costs"). Two shapes kept the old cost.

- **A store into a `long[]` or an array of references boxes its index**: the `GENERAL` arm of
  `emitKindedAset1` compiles the subscript boxed for `emitSimpleVectorAccess`. 10^8 stores
  into a `long[1000]` 0.68 s against an `int[]`'s 0.48
  (`.todo/artefacts/k76-java-wasm-what-is-left-of-an-arrays-making-and-store/`). Measure what a
  raw index saves there (the value is boxed too: a `long` past the fixnum range allocates)
  before building it.
- **`new T[n][m]` is `%java-new-array`**, whose `make-array` sizes are `(car lengths)`: nothing
  proves them integers, so every level takes the dims of any rank. Measure a program that makes
  a two-dimensional array in a loop, and YAVI's examples, for how often it occurs.
