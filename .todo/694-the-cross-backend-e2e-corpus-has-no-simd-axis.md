# 694. The cross-backend E2E corpus has no `--simd` axis, so half of every accelerated primitive is untested

Difficulty: Medium

`src/test/resources/ci-spec.yaml` is the single source of truth for `CiSpecE2eTest`, which
is what pins behaviour ACROSS the interpreter, the JVM and both wasm backends. **It never
passes `--simd`.** The only driver in the repo that does is `ExamplesE2eTest`, through the
`simd: true` flag on an `examples.yaml` entry -- seven of them today.

So a primitive can be pinned on all four backends by 1972 native cases and still be broken
on three of them, because "all four backends" is one axis and the accelerated path is a
second one nobody crosses with it.

## What this already cost

`.todo/671` closed 2026-09-03 claiming `float16-bits` / `bits-float16` /
`widen-float-bits` / `narrow-float-bits` on all four backends. It had `ci-spec.yaml` cases,
`LispEvaluatorTest`, `JvmLispCompilerTest` and `WasmLispCompilerIntegrationTest` entries,
and a green native run. **Under `--simd`, `widen-float-bits` and `narrow-float-bits`
trapped on both wasm outputs** -- `WasmFloat16Compiler` tested the packed array's data
field for `$f32arr` and cast the other arm to `$f64arr`, and under `--simd` that field is
a `TYPE_VBLOCK`, so the cast trapped. Every test the item wrote sat on the non-`--simd`
side of that condition. `.todo/692` fixed it.

**It was found by accident**: an unrelated lane was adding the wasm legs of
`.todo/675`'s safetensors fixture and happened to run the `simd: true` entry. Accident is
not a mechanism, and the next primitive to land on the vblock layout has nothing waiting
for it.

## Do

1. **Decide where the axis lives.** Two shapes, and the choice is the item:
   - a per-case `simd: true` in `ci-spec.yaml`, which the driver turns into a second
     compile of that case per backend -- precise, but the file is concatenated into ONE
     program per backend (`CLAUDE.md`), so a per-case flag means splitting the run, and
     the concatenation is what makes the suite cheap;
   - a whole second pass: run the entire corpus a second time with `--simd` on the
     backends that carry it, and require identical output. Coarser, one extra run, and it
     needs no change to the file's shape. **Measure what the second pass costs before
     choosing** -- if it is minutes, take it; the concatenation exists to keep the suite
     cheap and this doubles it.
2. **Whichever is chosen, `--no-gc` and `--parallel` are the same question.** Do not close
   this having covered only `--simd`; say explicitly which flags are axes and which are
   not, and why. `--parallel` changes reduction order rather than representation, so it
   may belong under a different rule; `--no-gc` refuses far more, so its axis is mostly
   about the refusals being the right ones.
3. **Delete nothing from `ExamplesE2eTest`.** Its `simd: true` entries exercise real
   programs; this item is about the corpus, which exercises primitives.

## Verify

- Re-introduce `.todo/692`'s bug (revert `WasmFloat16Compiler`'s `Layout` switch) and
  confirm the corpus goes red without any example being touched. **An axis that cannot
  catch the defect that motivated it is not the axis.**
- The added run's wall-clock, recorded here, against the suite's total.
- `.kb/vec.md`'s rule ("a test matrix that counts backends and not `--simd` has a hole")
  gains the E2E half; it currently closes only the unit half.
