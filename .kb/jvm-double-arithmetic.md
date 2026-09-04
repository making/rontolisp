# JVM double-float arithmetic (the unboxed IEEE path)

**Invariant: every unboxing shortcut here must answer what the generic `_add`/`_mul`/`_cmpb`
family answers for the same operands, bit for bit -- the boxes it removes are boxes the
generic path allocated and immediately unwrapped, never a different computation.**

Siblings: `.kb/jvm-int-fusion.md` (trees of `Long`s), `.kb/jvm-typed-loops.md` (packed
float arrays under `dotimes`). This file carries ORDINARY float code -- a `defun` doing
`double-float` arithmetic in a loop the typed-loop subset rejects
(`bench-report/programs/mandelbrot.lisp`).

## Routing predicate

`JvmLispCompiler.hasDoubleLiteral(args)` (recursive, via `containsDouble`) decides per NODE
whether an arithmetic form takes the unboxed IEEE path. It is a syntactic guess, not type
inference: the unboxed path coerces every operand through `_dbl`, which accepts `Long`,
`BigInteger`, `Double` and a ratio, so a wrong guess still gives a defined float result.
`INTEGER_VALUED_FORMS` (`round`, `truncate`, `floor`, `ceiling`) stop the recursion.

## What the path avoids

- `_dbl` no longer re-boxes a value that is already a `Double` (it did so for every operand
  of every double-path operation, including in `_add`, `_mul`, `_div`, `_cmp`, `_cmpb`,
  `_abs`, `_signum`, `_pow`). Callers unbox immediately, so box identity is unobservable.
- A literal round trip: `JvmArithCompiler.compileUnboxedOperand` pushes the constant raw.
  An INTEGER literal in a double operand position pushes `(double) v`, exactly the widening
  `_dbl` would do.
- A box between interior nodes: `compileUnboxedOperand` recognises an operand that is
  itself a `+ - * / mod rem` node the routing predicate claims and emits it inline; only
  the outermost node boxes.

Entry points using `compileUnboxedOperand`: `JvmArithCompiler`, `JvmComparisonCompiler`,
`JvmMathFnCompiler`, `JvmAbsCompiler`, `JvmExptCompiler`. Unrecognised operands compile as
ordinary expressions and unbox, so ratios, bignums, `nil` and error shapes are unchanged.

## `min`/`max` use the STRICTER predicate

`JvmMinCompiler`/`JvmMaxCompiler` gate on `JvmLispCompiler.isDefinitelyDouble`, NOT
`hasDoubleLiteral`. `min`/`max` return one operand AS IT STANDS (no contagion,
`.kb/linalg-simd.md`), so reboxing the wrong one as a `Double` changes its TYPE:
`(min 1 2.0)` answered `1.0` instead of `1` -- the JVM backend disagreeing with itself.

`isDefinitelyDouble` requires EACH operand independently proven double: a `LispDouble`
literal, a declared/raw double local (a checked-cast-backed guarantee,
`.kb/declarations-type-checks.md`), or recursively a `+`/`-`/`*`/`mod`/`rem` tree with at
least one provably-double operand (true CLHS contagion). Recursion never crosses an
arbitrary function call or `min`/`max` themselves. When both pass, both are unboxed via
`compileUnboxedOperand`, compared through the `_fmin`/`_fmax` raw-double helpers, and the
winner reboxed. Otherwise fall back to the general boxed `_min`/`_max`.

**Trap for future work**: the `mod`/`rem` arm of `isDefinitelyDouble` assumes `mod`/`rem`
answer a double whenever EITHER argument is a double (a TYPE claim, unrelated to the
zero-remainder SIGN question). If a change ever makes their result TYPE depend on which
operand is which, this arm must move with it or the fast path reaches a nested
`(mod ...)`/`(rem ...)` node whose value is not always double.

## The all-Double fast path in the fused methods

`.kb/jvm-int-fusion.md`'s `_fx$N` methods guard every leaf `instanceof Long`; a tree over
float variables failed that guard every evaluation. Such a method now carries a SECOND fast
path, tried where the Long guards used to jump:

```
guards (instanceof Long)  ---------> doubleEntry
tryStart: raw long tree; box; return          -- ArithmeticException region
doubleEntry: guards (instanceof Double) -----> bail
             raw double tree; box; return
handler (overflow): pop  ---------> falls through
bail: the generic-helper recompute; return
```

- **Only `+ - *` and the compare root** carry over: `mod`/`rem` and the bitwise operators
  are integer-only, and a packed-`aref` leaf reads a `long[]`.
- Every non-constant leaf is guarded STRICTLY (`instanceof Double`; an unboxed
  dual-representation local whose flag says the raw `long` slot is authoritative fails).
  No mixing -- a `Long` beside a `Double` bails to the generic path. An integer CONSTANT
  widens at emit time.
- The double path sits OUTSIDE the exception region: an overflow means the exact integer
  result did not fit a `long`, which the generic fallback owns (promotes to `BigInteger`).
- Comparisons use javac's NaN rule -- `DCMPG` for `<`/`<=`, `DCMPL` otherwise -- exactly
  the bitmask `_cmpb` answers.
- Slots are ordinary `allocTemp` pairs; a method that would push `nextLocal` past **250**
  emits no double path (one-byte slot operands).

## Other emission rules

- **Float-initialised `let` declines the integer dual representation**:
  `JvmIntFusionCompiler.rawBindingEligible` refuses a float-contaminated INIT and
  `isRawAssignShaped` a float-contaminated assignment value (the raw `long` slot would
  never be filled).
- **`setq` in statement position**: `JvmExprCompiler.compileForEffect` handles a form whose
  value is discarded (`progn` non-final forms, `tagbody` forms, `unwind-protect` cleanups,
  `while` body forms). Its special case `JvmSetqCompiler.compileForEffect` stores and
  leaves the stack empty when all targets are unboxed dual-representation locals, instead
  of pushing the value back through `_ubRead` for a `pop`.

## `_mod` and `_rem` over floats and ratios

Both had only a `Long` fast path and a `BigInteger` fallback, so a `Double` died casting to
`BigInteger` (reachable whenever the emitter could not see the operand type, including from
a fused site's bail). They now begin with the same double prologue as the other binary
helpers: `mod` through `_fmod` (CL's divisor-signed float modulo), `rem` through `DREM`
(dividend's sign).

A RATIO operand fell past the `Long` guard into `_big`. Both helpers now carry the
`_ratnum`/`_ratden` prologue `_add`/`_sub`/`_mul` have (`emitRatioGuard`, taken if EITHER
operand is a ratio): with a = an/ad, b = bn/bd, a/b is `(an*bd)/(ad*bn)`, so the integer
remainder of that division read over the common denominator `ad*bd` is the answer -- one
`_rat` call, which reduces and demotes. Denominators are positive, so the quotient's
denominator carries the DIVISOR's sign and `_mod`'s `emitDivisorSignCorrection` (now shared
with the `BigInteger` arm) applies unchanged. Both wasm backends already answered
rationals; the interpreter's matching arm is `Environment.rationalRemainder`. Pinned across
four backends by ci-spec `ratio-mod-rem`.

## Gates

None. Unlike fusion and typed loops this is not a speed-for-size trade -- every change here
makes the emitted code SMALLER as well as faster, so `--optimize=size` gets it too. The
double fast path inside `_fx$N` rides the fusion's own `Ctx.intFusion` gate.

## Declared floats: routing + raw double slots

`am.ik.rontolisp.compiler.DeclaredScalarTypes` (beside `DeclaredArrayTypes`) reads the
float family -- `double-float`/`single-float`/`short-float`/`long-float`/`float`, bare or
bounded, through deftype aliases -- out of body heads. Integer declarations are
deliberately NOT read (the fusion infers). Registration mirrors the wasm array kinds:
defun/lambda setup (`functionBodyDeclaredDoubles`, behind the sole trailing
`%fn-block`/`block` wrapper), `JvmLetCompiler` (bound and free declarations; let* nests, so
only its INNERMOST binding is bound-declared), the inline-lambda binder. Specials never
register, shadowed names drop out, `Ctx.declaredDoubles` is restored on scope exit.

- **Routing**: `containsDouble(val, ctx)` counts a declared (or raw-slotted) variable as a
  double literal, so `(* zr zr)` takes the unboxed IEEE path, routes away from int fusion,
  and inlines as an interior node. A declared variable on the routed path unboxes through
  `checkcast Double` (`JvmEmitHelper.unboxDeclaredDouble`), NOT `_dbl` -- the
  false-declaration policy of `.kb/declarations-type-checks.md`; coercion stays only where
  a genuine literal routed the node.
- **Raw double slots** (`Ctx.rawDoubleLocals`, name -> 2-slot base): a plain lexical let
  binding covered by a BOUND float declaration -- not special, not captured, not a
  duplicate, not a promoted-global name (program-wide set, so a top-level let of the same
  name declines the defun-local binding too), not under `--dynamic`, no nested defun in the
  body, within slot budget. The slot is ALWAYS authoritative (no flag, no shadow, unlike
  the integer dual representation): reads in routed positions `dload`, reads elsewhere box
  fresh (`compileSymbolRef`), assignments compile raw when routing claims it and otherwise
  land through the strict cast (`JvmSetqCompiler.compileRawDoubleValue`; an integer LITERAL
  widens at emit time). Skipped at top level by the promoted-global exclusion.
- **Statement position**: a let body's non-final forms compile through `compileForEffect`,
  and a let whose OWN value is discarded (every loop body after expansion) compiles its
  final form for effect too (`JvmLetCompiler.compileForEffect`, reached from
  `JvmExprCompiler.compileForEffect`; recurses through let*).
- **Interactions**: a raw double name is never in `locals`/`rawLocals`; `resolveRaw`
  declines it (a raw GLOBAL of the same name must not answer); the typed-loop compiler
  takes it as a free variable in place -- strictly `DOUBLE`, no entry guard, no typed copy,
  no write-back (`.kb/jvm-typed-loops.md`); the body outliner carries it across a `_k$N`
  split boxed, as with raw longs. A `handler-case` clause variable shadows raw longs, raw
  doubles AND the boxed set for the clause body (`compileClauseBody`) -- previously an
  outer RawLocal of the clause variable's name answered reads inside the clause.

## Performance shape (what to expect, not a benchmark log)

At steady state Graal's escape analysis already removes the boxes, so the boxed emission
matched or beat SBCL's declared build on the float rows. The raw-double emission buys the
COLD run and the C1-only tier (no escape analysis there), not the steady state. The
remaining cold-run distance to SBCL is JIT latency and tier-0 execution; SBCL pays its
compilation in `compile-file`. A JDK 25 Leyden AOT cache roughly halves the cold run but
only from a training run that reached steady state and only over a `-o app.jar` classpath
-- documented in `.kb/jvm-aot-cache.md`, deliberately kept out of the harness.

## Pinning tests

- `JvmLispCompilerTest.doubleArithmeticMatchesTheInterpreterOnBothOptimizeLevels` --
  literal widening, `-0.0`, the NaN comparison rule, a mixed Long/Double tree that bails
  off BOTH fast paths, a bignum leaf, `mod`/`rem` over floats, the float-initialised
  accumulator, the statement-position `setq` shapes; at `DEFAULT` and `SIZE`.
- ci-spec `double-arithmetic-unboxed-and-fused` (same answers on all four backends).
- `declaredFloatLocalsMatchTheUndeclaredEmissionOnBothOptimizeLevels` -- declared/undeclared
  twin equality, -0.0, NaN, ratio/bignum contagion, the captured / special / shadowed /
  top-level declines, handler-case clause-variable shadowing, and the three pinned UB
  shapes (:STORE-TRAP, :READ-TRAP, literal widening).
- ci-spec `declared-float-scalars-answer-what-undeclared-code-answers` (true declarations
  only -- a false declaration diverges across backends by policy).
