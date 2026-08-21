# `vec:matvec` is outside the `--blas` intercepted set

Difficulty: Medium

Surfaced the day `--blas` landed (2026-08-20). Read `.kb/linalg-blas.md` first -- this is
"do what `--blas` did for `linalg:dot`, for `vec:matvec`" -- and `.kb/vec.md` for the
package being accelerated. The `--blas` sibling of todo-467, which is the same gap one
level down: a shape the acceleration seam does not reach.

## The case

`--blas` intercepts `linalg:dot` and nothing else, so **not one of the numeric examples
this project ships is touched by it**: `simd-dot`, `simd-gemv`, `tiny-llm` and `llama2`
are all `vec:` programs, and an LLM decode is GEMV from end to end -- one weight matrix
times one activation vector, over and over. The documentation now says so in as many words
(`doc/{en,ja}/guides/simd-acceleration.md`, "Runnable examples"; the `--blas` half moved
to `doc/{en,ja}/guides/blas-acceleration.md`), which is an honest statement of a gap
rather than a design.

`cblas_?gemv` is the same C ABI, the same two downcall handles, and the same heap-segment
critical call the matrix product already uses. Measured on an Apple M4 Max against the
LANE kernel the emitted bridge runs today (`FloatVector.SPECIES_128` accumulating in f32
for `#f`, `DoubleVector.SPECIES_PREFERRED` for `#d`, both folding with `mul().add()`),
ms per matrix-by-vector product, re-runnable with
`.todo/471-vec-matvec-is-outside-the-blas-intercepted-set/GemvProbe.java`:

| rows x cols | lane kernel | `cblas_sgemv` | ratio | max rel diff |
| --- | --- | --- | --- | --- |
| 256x256 (`simd-gemv`) | 0.0048 | **0.0008** | 6.3x | 4.9e-05 |
| 288x288 (llama2 stories15M attention) | 0.0062 | **0.0009** | 7.0x | 3.2e-05 |
| 288x768 (its FFN up-projection) | 0.0188 | **0.0020** | 9.5x | 5.7e-04 |
| 768x288 (its FFN down-projection) | 0.0163 | **0.0020** | 8.0x | 4.1e-05 |
| 4096x288 (its classifier head) | 0.0880 | **0.0110** | 8.0x | 6.5e-03 |
| 2048x2048 | 0.4948 | **0.0605** | 8.2x | 7.8e-04 |

f64 (`#d`) is the same story: 6.9x at 256x256, 7.8x at 512x512, 8.0x at 2048x2048.

So it is worth 6-9x on exactly the shapes the flagship examples spend their time in, at
both widths, for a binding we have already written once. That is the whole case for the
item.

## What to intercept

- **`vec:matvec`** -- the member. `cblas_?gemv` is a literal match: `y = alpha A x + beta y`
  with `alpha = 1`, `beta = 0`.
- **`vec:matvec-into`** -- arguably the better fit of the two, because gemv writes into a
  caller-supplied `y` and the `-into` form already has one, so the interception removes the
  result allocation as well as the loop.
- **NOT `vec:dot` / `vec:sum` / the element-wise members.** They are memory-bound; a
  library call cannot beat a lane loop over the same bytes. Measure before assuming, but
  the matrix product's own decline set says the same thing.

## The three structural differences from the `linalg:dot` half

1. **`vec:` kernels are TOTAL, not partial.** `linalg:`'s kernels return the null sentinel
   for anything they decline and the call site falls through; `vec:` accepts packed float
   arrays of one width and SIGNALS on anything else, so its JVM call site is a bare
   `INVOKESTATIC` with no null guard (`JvmSimdCompiler`). The blas attempt therefore
   belongs INSIDE the bridge method -- `simdMatvec` tries the library and falls into its own
   lane loop when there is none -- rather than at the call site. Same on the interpreter:
   `VecSimd` installs a native that signals, so the attempt goes inside the native.
   That is simpler than the `linalg:` chain, not harder; it just is not the same mechanism.
2. **`--blas` alone has no `vec:` call site to hook.** The `vec:` members are only
   intercepted when `--simd` emitted the bridge, so a `--blas` build without `--simd` would
   run the scalar `vec.lisp` defun and never reach a library. Decide which: give `--blas`
   its own guarded `vec:` call site (the `linalg:` shape), or state that the `vec:` half of
   `--blas` needs `--simd`. The second is a smaller change and a worse contract; the first
   duplicates a seam. Neither is obviously right -- this is the item's real design
   question, and it is the same question `vec:`'s totality raises in point 1 from the other
   side.
   The emit gate moves either way: `JvmLispCompiler` emits the blas bridge only when
   `JvmLinalgBlas.QUALIFIED_DOT` is reachable.
3. **The pinned outputs may move, and this is the risk that makes the item Medium rather
   than Low.** The `#f` reduction contract already says a single-float fold accumulates in
   single precision; what changes is the ORDER, and the table above shows what that costs
   on a cancelling dot: up to 6.5e-3 relative on the classifier-head shape. `simd-gemv`
   prints `argmax` indices and `tiny-llm` prints token ids -- integers DERIVED from these
   reductions, chosen precisely because last-bit differences cannot move them. A 1e-3
   relative difference can. So every pinned expectation that rides a `vec:matvec` has to be
   re-verified under the flag, and if one moves, the honest fix is to say that `--blas`
   output is not compared against the other backends (the `--gpu` posture), not to loosen
   the example.

## Acceptance

- `vec:matvec` and `vec:matvec-into` run on `cblas_?gemv` under `--blas` on the interpreter
  (native binary included) and the JVM, at both widths, declining to the lane kernel on a
  machine with no tuned library.
- `simd-gemv`, `tiny-llm` and `llama2` measurably faster under `--blas`, with their printed
  integers verified rather than assumed.
- Tests in the shape of `LinalgBlasTest` / `LinalgBlasDeclineTest`: `@EnabledIf` on
  `LinalgBlas.available()` for the accelerated half, unconditional for the declined half,
  and a dead-flag guard that fails if the interception never fires.
- Docs: drop the "The `vec:` examples here are untouched by `--blas` and `--gpu` alike"
  sentence from `doc/{en,ja}/guides/simd-acceleration.md`, extend
  `doc/{en,ja}/guides/blas-acceleration.md` from "the matrix product and nothing else"
  to name the GEMV, and re-measure the header tables
  of `examples/ml/simd-gemv.lisp` and `examples/llama2/README.md`.
- `.kb/linalg-blas.md`'s "The intercepted set: the product, and nothing else" section is
  the file to rewrite, and its "the memory-bound members would gain nothing" claim is the
  one to keep -- it is why `vec:dot` stays out.

## References

- `.kb/linalg-blas.md` -- the binding, the marker rule, the critical-downcall model.
- `.kb/vec.md` -- the package, its four acceleration layers, the `#f` reduction contract.
- `.todo/467-batched-matmul-is-outside-the-simd-intercepted-set.md` -- the same shape of
  gap over the `--simd` seam; that item's member is a batch of gemms and would want this
  one's binding.
- `.todo/471-vec-matvec-is-outside-the-blas-intercepted-set/GemvProbe.java` -- the probe
  above, re-runnable on other hardware
  (`java --add-modules jdk.incubator.vector --enable-native-access=ALL-UNNAMED GemvProbe.java`).
  Throwaway probe code, not project code: outside `src/`, not in the reactor, not formatted.
