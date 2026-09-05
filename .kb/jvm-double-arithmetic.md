# JVM double-float arithmetic (the unboxed IEEE path)

**Invariant: every unboxing shortcut here must answer what the generic `_add`/`_mul`/`_cmpb`
family answers for the same operands, bit for bit** — the boxes it removes are boxes the generic
path allocated and immediately unwrapped, never a different computation. Siblings:
`.kb/jvm-int-fusion.md` (trees of `Long`s), `.kb/jvm-typed-loops.md` (packed float arrays under
`dotimes`). This file is ORDINARY float code (`bench-report/programs/mandelbrot.lisp`).

## Routing
- `JvmLispCompiler.hasDoubleLiteral` (recursive via `containsDouble`) decides per NODE. It is a
  syntactic guess, not type inference: the unboxed path coerces through `_dbl` (accepts `Long`,
  `BigInteger`, `Double`, ratio), so a wrong guess still gives a defined float result.
  `INTEGER_VALUED_FORMS` (`round`, `truncate`, `floor`, `ceiling`) stop the recursion.
- `JvmArithCompiler.compileUnboxedOperand` pushes literals raw (an integer literal as
  `(double) v`) and inlines an interior `+ - * / mod rem` node, so only the outermost node boxes.
  Users: `JvmArithCompiler`, `JvmComparisonCompiler`, `JvmMathFnCompiler`, `JvmAbsCompiler`,
  `JvmExptCompiler`. Unrecognised operands compile as ordinary expressions.
- **`min`/`max` use the STRICTER `JvmLispCompiler.isDefinitelyDouble`**, not `hasDoubleLiteral`:
  they return one operand AS IT STANDS (no contagion), so reboxing the wrong one changes its TYPE
  — `(min 1 2.0)` answered `1.0` instead of `1`. `isDefinitelyDouble` needs EACH operand
  independently proven (a `LispDouble` literal, a declared/raw double local, or a
  `+`/`-`/`*`/`mod`/`rem` tree with one provably-double operand); it never crosses a function call
  or `min`/`max`. Then `_fmin`/`_fmax`, else the boxed `_min`/`_max`. **Trap**: the `mod`/`rem`
  arm assumes they answer a double whenever EITHER argument is one — if their result TYPE ever
  depends on which operand is which, this arm must move with it.

## The all-Double fast path inside `_fx$N`
`.kb/jvm-int-fusion.md`'s fused methods guard leaves `instanceof Long`; they now carry a second
path where those guards used to jump: `doubleEntry` guards `instanceof Double`, runs a raw double
tree, boxes, returns; `bail` recomputes through the generic helpers.

- Only `+ - *` and the compare root carry over (`mod`/`rem` and bitwise are integer-only, a packed
  `aref` leaf reads a `long[]`).
- Every non-constant leaf is guarded STRICTLY — no mixing; a `Long` beside a `Double` bails. An
  integer CONSTANT widens at emit time.
- The double path sits OUTSIDE the exception region: an overflow means the exact integer result
  did not fit a `long`, which the generic fallback owns.
- Comparisons use javac's NaN rule — `DCMPG` for `<`/`<=`, `DCMPL` otherwise — exactly what
  `_cmpb` answers.
- A method that would push `nextLocal` past **250** emits no double path (one-byte slot operands).

## Other emission rules
- `JvmIntFusionCompiler.rawBindingEligible` refuses a float-contaminated let INIT and
  `isRawAssignShaped` a float-contaminated assignment (the raw `long` slot would never fill).
- `JvmExprCompiler.compileForEffect` / `JvmSetqCompiler.compileForEffect` store and leave the
  stack empty for a discarded `setq` over unboxed dual-representation locals.
- `_mod`/`_rem` now open with the double prologue (`mod` via `_fmod`, CL's divisor-signed float
  modulo; `rem` via `DREM`) and carry the `_ratnum`/`_ratden` ratio prologue (`emitRatioGuard`):
  with a = an/ad, b = bn/bd, the integer remainder of `(an*bd)/(ad*bn)` read over `ad*bd` is the
  answer — one `_rat` call; `emitDivisorSignCorrection` is shared with the `BigInteger` arm. The
  interpreter's matching arm is `Environment.rationalRemainder`. Pinned by ci-spec `ratio-mod-rem`.
- No gate: every change here makes the emitted code SMALLER as well as faster, so
  `--optimize=size` gets it too. The `_fx$N` double path rides fusion's `Ctx.intFusion`.

## Declared floats: routing + raw double slots
`compiler.DeclaredScalarTypes` (beside `DeclaredArrayTypes`) reads
`double-float`/`single-float`/`short-float`/`long-float`/`float`, bare or bounded, through deftype
aliases, out of body heads. Integer declarations are deliberately NOT read (fusion infers).
Registration: defun/lambda setup (`functionBodyDeclaredDoubles`, behind the sole trailing
`%fn-block`/`block`), `JvmLetCompiler` (let* nests, so only its INNERMOST binding is
bound-declared), the inline-lambda binder; specials never register, shadowed names drop out,
`Ctx.declaredDoubles` restored on scope exit.

- Routing: `containsDouble(val, ctx)` counts a declared or raw-slotted variable as a double
  literal. Such a variable unboxes through `checkcast Double`
  (`JvmEmitHelper.unboxDeclaredDouble`), NOT `_dbl` — the false-declaration policy of
  `.kb/declarations-type-checks.md`.
- Raw double slots (`Ctx.rawDoubleLocals`, name -> 2-slot base) need a plain lexical let binding
  under a BOUND float declaration: not special, not captured, not a duplicate, not a
  promoted-global name (program-wide set), not `--dynamic`, no nested defun, within slot budget.
  The slot is ALWAYS authoritative (no flag, no shadow, unlike the integer dual representation):
  routed reads `dload`, other reads box fresh (`compileSymbolRef`), assignments compile raw or
  land through the strict cast (`JvmSetqCompiler.compileRawDoubleValue`).
- Interactions: a raw double name is never in `locals`/`rawLocals` and `resolveRaw` declines it;
  the typed-loop compiler takes it as a free variable in place, strictly `DOUBLE`, no guard/copy/
  write-back (`.kb/jvm-typed-loops.md`); the body outliner carries it across a `_k$N` split boxed.
  A `handler-case` clause variable shadows raw longs, raw doubles AND the boxed set
  (`compileClauseBody`).

## Performance shape
Graal's escape analysis already removes the boxes at steady state, so the raw-double emission buys
the COLD run and the C1-only tier, not the steady state. A JDK 25 Leyden AOT cache roughly halves
the cold run, from a steady-state training run over a `-o app.jar` classpath
(`.kb/jvm-aot-cache.md`, kept out of the harness).

## Tests
`JvmLispCompilerTest.doubleArithmeticMatchesTheInterpreterOnBothOptimizeLevels` and
`.declaredFloatLocalsMatchTheUndeclaredEmissionOnBothOptimizeLevels` (twin equality, -0.0, NaN,
ratio/bignum contagion, the captured/special/shadowed/top-level declines, handler-case shadowing,
the three pinned UB shapes :STORE-TRAP/:READ-TRAP/literal widening); ci-spec
`double-arithmetic-unboxed-and-fused` and `declared-float-scalars-answer-what-undeclared-code-answers`
(true declarations only — a false declaration diverges across backends by policy).
