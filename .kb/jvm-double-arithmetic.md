# JVM double-float arithmetic (the unboxed IEEE path)

**Invariant: every unboxing shortcut here must answer what the generic `_add`/`_mul`/`_cmpb`
family answers for the same operands, bit for bit** — the boxes it removes are boxes the generic
path allocated and immediately unwrapped, never a different computation. Siblings:
`.kb/jvm-int-fusion.md` (trees of `Long`s), `.kb/jvm-typed-loops.md` (packed float arrays under
`dotimes`). This file is ORDINARY float code (`bench-report/programs/mandelbrot.lisp`).

## Routing
- `JvmLispCompiler.hasDoubleLiteral` (recursive via `containsDouble`) decides per NODE. It is a
  syntactic guess, not type inference: the unboxed path coerces through `_dbl` (accepts `Long`,
  `BigInteger`, `Double`, ratio). For `+ - * / mod rem` the raw fold runs only where
  `isDefinitelyDouble` proves an operand ("The exact prefix" below), and a one-operand site
  only where it proves that operand ("One-operand sites" below).
  `INTEGER_VALUED_FORMS` (`round`, `truncate`, `floor`, `ceiling`) stop the recursion.
- **`_dbl`'s ratio arm is the generated `_ratToDouble`: the correctly-rounded nearest
  double** (round-half-even over a 56-bit `BigInteger` head plus the remainder as the
  sticky bit, denormalizing to signed zero), bit-identical with
  `LispRatio.doubleValue` and WASM's `_rat_to_f64`. A `BigDecimal` DECIMAL64
  step used to sit here and rounded exact quotients to 16 decimal digits first
  (`1/8388608` answered `1.192092895507812e-7`, not `2^-23`); the ANSI
  `RATIONAL.1`/`RATIONALIZE.1`/`RATIONALIZE.3`/`/.12` round trips plus `*.12` pin the
  fix on every backend. The method costs ~300 bytes in each numeric program (the
  8,000-byte budget in
  `JvmLispCompilerTest#aProgramThatNeverNamesAnArrayOperatorCarriesNoArrayRuntime`
  went to 8,400 for it, 8,295 actual); a may-produce-a-ratio gate would buy that back
  but needs the complex gate's recompile net to stay sound (a missed source is a
  `NoSuchMethodError`, not a wrong answer), which is disproportionate -- the budget
  still catches what it was built for.
- `JvmArithCompiler.compileUnboxedOperand` pushes literals raw (an integer literal as
  `(double) v`) and inlines an interior `+ - * / mod rem` node, so only the outermost node boxes;
  the inlined node runs under its own operator and source site, so its `_dbl` reports it, not
  the operation it is an operand of. Users: `JvmArithCompiler`, `JvmComparisonCompiler`,
  `JvmMathFnCompiler`, `JvmAbsCompiler`, `JvmExptCompiler`. Unrecognised operands compile as
  ordinary expressions. A site with more than one operand pushes them through
  `compileUnboxedOperands`, which holds back a conversion that can fail until every later
  observable operand ran (`.kb/argument-evaluation-order.md`, "An operation applies after its
  operands").
- In a program that may observe a complex, a site whose operands may hold one takes
  `JvmFloatOperands` instead: the same raw arithmetic when no operand is a holder, the generic
  helpers when one is, each operation applied where the interpreter applies it
  (`.kb/jvm-complex.md`, "A complex beside a float literal").
- **`min`/`max` use the STRICTER `JvmLispCompiler.isDefinitelyDouble`**, not `hasDoubleLiteral`:
  they return one operand AS IT STANDS (no contagion), so reboxing the wrong one changes its TYPE
  — `(min 1 2.0)` answered `1.0` instead of `1`. `isDefinitelyDouble` needs EACH operand
  independently proven (a `LispDouble` literal, a declared/raw double local, or a
  `+`/`-`/`*`/`/`/`mod`/`rem` tree with one provably-double operand and no complex literal); it never
  crosses a function call or `min`/`max`. Then `_fmin`/`_fmax`, else the boxed `_min`/`_max`. **Trap**: the `mod`/`rem`
  arm assumes they answer a double whenever EITHER argument is one — if their result TYPE ever
  depends on which operand is which, this arm must move with it.
- **Comparisons use the same STRICTER gate since `.todo/037`'s float-vs-exact fix**:
  a float beside an exact number compares EXACT values (the float's exact binary value,
  as `rational` answers it), so `(= 1.0 (+ 1 tiny-ratio))` is false even though the ratio
  floats back to `1.0` — the old unboxed DCMPL answered float-contagion and was wrong for
  ratios, bigints and fixnums past 2^53 alike. `JvmComparisonCompiler` takes the DCMPL path
  only when BOTH operands are `isDefinitelyDouble`; anything else goes through `_cmpb`,
  whose mixed arm decomposes the double through `_frat` and cross-multiplies against the
  exact operand's (`_ratNum`, `_ratDen`) — the interpreter's `compareFloat` in bytecode
  (NaN unordered, infinities beyond every exact number on their side, the non-number
  funnel staying a NUMBER operand-type report). `_cmp`'s prologue mirrors it for the complex parts
  (a NaN jumps back to the old DCMPL, which collapses it to -1 as before). The ANSI
  `*.17`/`*.18` + `BIGNUM.FLOAT.COMPARE.1A-4B` pin the interpreter; `JvmLispCompilerTest`
  pins the JVM call-site gate (a double LITERAL beside a computed ratio) and the funnel.

## The exact prefix
CL's n-ary `+ - * /` is a left fold of the two-argument step, and a step floats only once one
of ITS operands is a float, so the arguments ahead of the first float fold exactly:
`(+ 1/10 1/5 0.0)` is `0.3`. Measured 2026-10-07, SBCL 2.2.9 (`*read-default-float-format*`
`double-float`, operands literal or through `notinline` identity calls): `0.3` for the `+`,
`-`, `*`, `/` rows, `9.007199254740994e15` for `(+ 9007199254740993 1 0.0)`,
division-by-zero for `(/ 1/2 0 1.0)`. Before that day the interpreter (`hasDouble`, one double
loop) and the JVM converted every argument first (`0.30000000000000004`, `...992e15`,
Infinity); WASM did too on a float-literal site and for `/` through calls, and folded `+ - *`
through calls pairwise (fusion took them).

- `compiler/FloatFold.exactPrefix`: the raw fold runs from the first operand iff operand 0 or
  1 is proven a float (`isDefinitelyDouble`) -- then every step is a float step and converting
  each operand IS the pairwise fold, so the site's bytes do not change. Otherwise the operands
  ahead of the first proven float fold through the generic helpers, and only the last of
  those steps differs: `_addd`/`_subd`/`_muld`/`_divd` (`(OO)D`, wrapped per operator like
  `_add`; WASM `_rat_add_f64` .. `_rat_div_f64`, type `TYPE_RAT_STEP_F64`) return the raw
  double of the step. No proven float at all: the generic fold IS the site (a boxed site
  keeps the generic value -- exact operands answer exactly; an inlined inner site ends on the
  `_addd` step, which the parent converts anyway).
- Why that helper and not `_dbl(_add(a, b))`: a Double first operand is read straight out of
  its box and the other goes through `_dbl`, the same tests `_dbl(a)`, `_dbl(b)` made on the old
  raw path, so floats at an unproven site pay nothing new; the composition boxes the step's
  float (a `TYPE_FLOAT` allocation on wasmtime, which has no escape analysis). A run-time
  `instanceof` branch at the site was rejected: a test per site, plus temporaries for every
  later operand of the branch. Left: a site with three or more unproven operands ahead of the
  first float boxes one value per extra step when they hold floats (counted 2026-10-07 over
  `examples/`, `src/main/resources`, `bench-report/` and `size-report/`: 16 float sites with
  an unproven pair ahead of their first proven float, 4 of them with three).
- Interpreter: `Environment.floatFold` (exact fold of the prefix, then doubles). JVM:
  `JvmArithCompiler.compileFold` and `JvmFloatOperands.foldRaw` (its prefix operands evaluated
  boxed). WASM: `WasmArithCompiler.compileFold`, `compileGuarded`. The fused double path folds
  the integer constants a node starts with exactly at emit time (`JvmIntFusionCompiler.
  exactConstant`), since a `Long` leaf bails there anyway.
- Pins: `ExactPrefixFloatFoldFixture` (`LispEvaluatorTest`, `JvmLispCompilerTest` both levels,
  `WasmLispCompilerIntegrationTest` every level and the component).

## One-operand sites
`(- x)`, `(/ x)`, `abs`, `signum`, `random` and `expt` (base or power) convert their operand
only where `isDefinitelyDouble` proves it (`FloatFold.exactPrefix` answers the operand count
for a lone unproven operand); a float literal anywhere else in it (`(abs (if c 1.5 -2))`) no
longer makes an exact value a float. Measured 2026-10-07, SBCL 2.2.9 = interpreter: `2`, `-2`,
`1/4`, `-1`, `8`, `(integerp (random ...))` T; before that day the JVM answered `2.0 -2.0 0.25
-1.0 8.0 NIL`, P1 and the component `2.0 -2.0 0.25 -1 8 NIL` (WASM's `signum`/`expt` never
routed on the literal), and `(/ (if c 2.0 0))` was Infinity, not division-by-zero.

- Unproven: the site is what it is without a float literal -- `_neg`, `_div(1, x)`, `_abs`,
  `_signum`, `_pow`, `_random` (WASM: `compileUnaryNegate`, `_rat_div`, the `abs` type ladder,
  `random`'s `ref.test TYPE_FLOAT` path). Each already dispatches on the value, so a float
  there costs the helper's type dispatch, and a proven site emits exactly what it did: no
  run-time test is added to a site whose operand is proven a float.
- Inside a raw site (`compileUnboxedOperand` inlining, `JvmFloatOperands.compileInner`) an
  unproven `(/ x)` ends on `_divd(1, x)`: `1.0 / dbl(x)` rounds twice and differs from the
  double of the exact reciprocal for a bignum past 2^53 (`(* 1.0 (/ 9007199254740993))` is
  `...564e-16`, not `...565e-16`). An unproven `(- x)` keeps DNEG: negation is exact and the
  conversion is sign-symmetric, so `-dbl(x)` IS `dbl(-x)`. WASM compiles an inner operation
  as a call, so it gets the exact reciprocal from the top-level arm.
- Pins: `OneOperandFloatSiteFixture` (`LispEvaluatorTest#oneOperandFloatSite`,
  `JvmLispCompilerTest#compileAndRunOneOperandFloatSite` both levels,
  `WasmLispCompilerIntegrationTest#oneOperandFloatSite` every level and the component).

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
