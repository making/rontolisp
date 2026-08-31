# The JVM backend loads a big model slower than the interpreter now does

Difficulty: High

Measured 2026-08-31, Apple M4 Max, the same 155 MB scanned hand (1,062,622 v /
2,123,160 f) through `geom:read-obj` -> `mesh` -> `wireframe` -> `bounds` ->
`geom::%model-extent`:

| stage | interpreter (natives, `eval/GeomKernels`) | `-o Bench.class` |
|---|---|---|
| `read-obj` | 724 ms | 3,542 ms |
| `mesh` | 137 ms | 902 ms |
| `wireframe` | 213 ms | 1,157 ms |
| `bounds` | 16 ms | 665 ms |
| `%model-extent` | 5 ms | 539 ms |
| **total** | **1,193 ms** | **6,805 ms** |

Both print the same bounds to the last digit. **The interpreter is 5.7x faster
than the compiled class**, which inverts the whole premise of `.kb/geom.md`'s
"Measured" table, and it is not because the JVM backend got worse: the
interpreter stopped running the Lisp at all for those five members
(`.kb/geom.md`, "The interpreter's native kernels") while the JVM backend still
compiles `%scan-number`'s character loop, `%facet-normal`'s Newell sum and
`%la-matmul`'s triple loop into bytecode and runs them a few hundred million
times.

## What the fix looks like

The pattern already exists one library over: `codegen/jvm/JvmLinalgKernelCompiler`
is a CALL-SITE compiler that recognizes a `linalg:` call and emits a call into an
embedded bridge class (`JvmBlasTemplate`, `JvmSimdTemplate`), with the argument
forms evaluated once into temps and an `IFNONNULL` chain to the fallback
(`.kb/linalg-blas.md`). A `JvmGeomKernelCompiler` would do the same for
`geom:read-obj` / `geom:mesh` / `geom:wireframe` / `geom::%vertex-extremes`,
against a `JvmGeomTemplate` that is `eval/GeomKernels`'s kernel half with the
`LispVal` types swapped for the JVM backend's representation.

Two things to settle before writing it:

- **What travels.** The template class is EMBEDDED in the compiled output the
  way `JvmGpuRuntimeBuilder` and `JvmObjcRuntimeBuilder` embed theirs, so it
  joins the list in `.kb/jvm-export.md` "What travels" and its class list has to
  follow the package. Weigh that against the win: a program that reads no model
  file must not carry it, which means the emit has to be gated on the call site
  the way the linalg kernels are, not on the library splice.
- **The same bit-identity rule.** The JVM template must agree with the JVM
  DEFUN, which is where the interpreter's rule ("the native is the defun
  transcribed, or it declines") has to be restated for a backend whose float
  representation is its own. `ci-spec.yaml`'s `geom-read-model-cross-backend`
  is the pin, and it must not need a new expectation.

WASM is the same argument again, and further off: no `float[]` to pack into
without the GC array types the backend already uses, and the preview-1 I/O
adapter reads through its own stream layer. Do the JVM first and see whether the
shape generalizes.
