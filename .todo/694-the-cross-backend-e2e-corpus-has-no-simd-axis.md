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

## `--simd` fails OPEN, so the axis is not done when the flag is passed

`cli/RontoLispCli.enableSimd` probes for the incubator module and, when it is missing,
**warns on stderr and runs the scalar kernels**:

```java
if (VecSimd.available()) { evaluator.setSimd(true); }
else { warn("--simd: jdk.incubator.vector is unavailable, running the scalar ..."); }
```

And `CiSpecE2eTest`'s stderr assertion is a per-expected-line `contains`, not an
exclusion -- **a warning nobody expected does not turn a case red.** So a naive `--simd`
axis goes green with the flag doing nothing, which is the failure this item exists to
kill, rebuilt inside its own fix.

The native binary bakes the module in, so ci-spec's own backends are safe; the exposure is
any leg run under plain `java` (a `-o Prog.class` output), which needs
`--add-modules jdk.incubator.vector`. **`ExamplesE2eTest` already handles this** (two
sites, both commented "without it `--simd` warns and runs scalar"). A second mechanism
that repeats the omission is the likely outcome unless the design says otherwise.

**`--simd` is semantically transparent -- a program cannot observe whether it took
effect. The absence of that warning on stderr is the only positive evidence there is**, so
assert on it rather than on any output.

## Do

1. **Decide where the axis lives.** Two shapes, and the choice is the item:
   - a per-case `simd: true` in `ci-spec.yaml`, which the driver turns into a second
     compile of that case per backend -- precise, but the file is concatenated into ONE
     program per backend (`CLAUDE.md`), so a per-case flag means splitting the run, and
     the concatenation is what makes the suite cheap;
   - a whole second pass: run the entire corpus a second time with `--simd` on the
     backends that carry it, and require identical output. Coarser, one extra run, and it
     needs no change to the file's shape;
   - **`standalone:`, which already runs one case as one program per backend**
     (`CiSpecE2eTest`), for cases that cannot be concatenated because running them ends
     the process. Giving it a `flags:` field breaks neither the concatenation nor doubles
     the run: the cost is one process per flagged case, on a mechanism already built and
     already paid for on every backend. **If this is taken, say so in the record's
     javadoc** -- its stated reason is "running it ENDS the program", and a second reason
     sharing one mechanism is how the next reader misses the axis.

   **Measure before choosing**: the number to get is the ratio of per-RUN fixed cost
   (binary start plus compile) to per-CASE marginal cost. Fixed-cost-dominated makes the
   second pass nearly free; marginal-cost-dominated makes doubling really double. With 492
   corpus cases against 1 standalone today, fixed cost is the likely answer -- **which is
   a prediction, not a measurement.**
2. **Whichever is chosen, `--no-gc` and `--parallel` are the same question.** Do not close
   this having covered only `--simd`; say explicitly which flags are axes and which are
   not, and why. `--parallel` changes reduction order rather than representation, so it
   may belong under a different rule; `--no-gc` refuses far more, so its axis is mostly
   about the refusals being the right ones.
3. **Rejected, and recorded so it is not re-proposed: a second pass over only the cases
   that touch packed floats.** It looks like the obvious middle between coarse doubling
   and per-case precision, and it rebuilds exactly the condition this item kills. The
   subset is chosen by judgement, so the next defect surfaces in a case nobody classified
   as relevant -- and `.todo/692` was found in precisely that shape, through an unrelated
   lane's fixture. A "touches packed floats" filter would have caught 692, but only in
   hindsight; as a mechanism it is the same bet again.
4. **Delete nothing from `ExamplesE2eTest`.** Its `simd: true` entries exercise real
   programs; this item is about the corpus, which exercises primitives.

## Verify

- Re-introduce `.todo/692`'s bug (revert `WasmFloat16Compiler`'s `Layout` switch) and
  confirm the corpus goes red without any example being touched. **An axis that cannot
  catch the defect that motivated it is not the axis.**
- **Run it with `jdk.incubator.vector` off the module graph and require the `--simd`
  cells to go RED.** Green there means the axis asserts that the flag was passed, not that
  it did anything -- see the fail-open section above.
- The added run's wall-clock, recorded here, against the suite's total, with the
  fixed-vs-marginal split that decided the shape.
- `.kb/vec.md`'s rule ("a test matrix that counts backends and not `--simd` has a hole")
  gains the E2E half; it currently closes only the unit half.
