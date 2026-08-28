# JVM double-float arithmetic (the unboxed IEEE path)

**Invariant: every unboxing shortcut here must answer what the generic `_add`/`_mul`/`_cmpb`
family answers for the same operands, bit for bit -- the boxes it removes are boxes the
generic path allocated and immediately unwrapped, never a different computation.**
The JVM backend's integer story is `.kb/jvm-int-fusion.md` (trees of `Long`s) and
`.kb/jvm-typed-loops.md` (packed float arrays under `dotimes`); this file is the third
piece, the one that carries ORDINARY float code -- a `defun` doing `double-float`
arithmetic in a loop the typed-loop subset rejects, which is what
`bench-report/programs/mandelbrot.lisp` is.

## The routing predicate

`JvmLispCompiler.hasDoubleLiteral(args)` (recursive, via `containsDouble`) decides per
NODE whether an arithmetic form takes the unboxed IEEE path or the generic helpers. It is
a syntactic guess about the operand types -- a double literal anywhere under an operand
routes the node -- and it is deliberately not a type inference: the unboxed path coerces
every operand through `_dbl`, which accepts `Long`, `BigInteger`, `Double` and a ratio, so
a wrong guess is still a defined float result. `INTEGER_VALUED_FORMS` (`round`,
`truncate`, `floor`, `ceiling`) stop the recursion, because those forms answer an integer
whatever their arguments are.

## Three costs the path used to pay, and does not

- **`_dbl` re-boxed a `Double`.** It ended `Number.doubleValue()` +
  `Double.valueOf(...)` for every operand of every double-path operation, INCLUDING one
  that was already a `Double` -- one allocation per operand, in the generic helpers too
  (`_add`, `_mul`, `_div`, `_cmp`, `_cmpb`, `_abs`, `_signum`, `_pow`, ...). It now
  answers such a value as-is. Every caller unboxes the result immediately, so the box's
  identity is unobservable.
- **A literal round trip.** A `1.5d0` operand compiled as `Double.valueOf(1.5)` and was
  then unboxed by `_dbl` + `checkcast` + `doubleValue`. `JvmArithCompiler`'s
  `compileUnboxedOperand` pushes the constant raw instead. An INTEGER literal in a double
  operand position pushes `(double) v`, which is exactly the widening `_dbl` of the
  `Long` performed.
- **A box between interior nodes.** `(+ (* 2.0d0 zr zi) ci)` compiled the inner `*`
  through `compileExpr` -- which boxed at its root -- and then unboxed it again.
  `compileUnboxedOperand` recognises an operand that is ITSELF a `+ - * / mod rem` node
  the routing predicate claims, and emits it inline; only the outermost node boxes. The
  recursion is exact because the inlined node's boxed emission was the same left fold
  over the same raw operands.

The entry points that took `compileExpr` + `unboxDouble` and now take
`compileUnboxedOperand`: `JvmArithCompiler`, `JvmComparisonCompiler`, `JvmMathFnCompiler`,
`JvmAbsCompiler`, `JvmMinCompiler`, `JvmMaxCompiler`, `JvmExptCompiler`. Anything the
operand rule does not recognise still compiles as an ordinary expression and unboxes, so
ratios, bignums, `nil` and every error shape are unchanged.

## The all-Double fast path in the fused methods

`.kb/jvm-int-fusion.md`'s `_fx$N` methods guard every leaf `instanceof Long` and bail to
the generic recompute when one is not. A tree over float variables -- `(* zr zr)`,
`(+ (- zr2 zi2) cr)`: no literal, so the routing predicate does not claim it -- failed
that guard on every single evaluation. Such a method now carries a SECOND fast path,
tried where the Long guards used to jump:

```
guards (instanceof Long)  ---------> doubleEntry
tryStart: raw long tree; box; return          -- ArithmeticException region
doubleEntry: guards (instanceof Double) -----> bail
             raw double tree; box; return
handler (overflow): pop  ---------> falls through
bail: the generic-helper recompute; return
```

- **Only `+ - *` and the compare root** carry over. `mod`/`rem` and the bitwise operators
  are integer-only, and a packed-`aref` leaf reads a `long[]`, so a tree containing one
  emits the long path and the fallback alone.
- **Every non-constant leaf is guarded STRICTLY** (`instanceof Double`; an unboxed
  dual-representation local whose flag says the raw `long` slot is authoritative fails
  immediately). No mixing: a `Long` beside a `Double` bails to the generic path, which is
  where float contagion is already exact. An integer CONSTANT in the tree widens at emit
  time, exactly as `_dbl` widens the `Long` the generic path would have seen.
- **The double path sits OUTSIDE the exception region.** An overflow means the exact
  integer result did not fit a `long`, which the generic fallback owns (it promotes to
  `BigInteger`) -- doubles are not the answer to it.
- **Comparisons use javac's NaN rule** -- `DCMPG` for `<`/`<=`, `DCMPL` for the rest --
  which is exactly the bitmask `_cmpb` answers: a NaN operand fails every operator on
  both paths.
- Slots are the ordinary `allocTemp` pairs; a method that would push `nextLocal` past 250
  emits no double path (one-byte slot operands).

## A float-initialised `let` binding declines the integer dual representation

`JvmIntFusionCompiler.rawBindingEligible` now refuses a binding whose INIT is
float-contaminated, and `isRawAssignShaped` refuses a float-contaminated assignment value.
The dual representation (raw `long` + boxed shadow + flag) is for integer accumulators;
for `(let ((zr 0.0d0) (zi 0.0d0)) ...)` the raw slot is never filled, so every read paid
`_ubRead` and every store paid the per-site type dispatch to always land in the shadow.

## `setq` in statement position

`JvmExprCompiler.compileForEffect` compiles a body form whose value is discarded --
`progn`'s non-final forms, a `tagbody` form, an `unwind-protect` cleanup, a `while` body
form. Its one special case is `JvmSetqCompiler.compileForEffect`: an assignment whose
targets are ALL unboxed dual-representation locals stores and leaves the stack empty,
instead of pushing the value back through `_ubRead` for the caller to `pop`. Every counted
loop's counter step and every loop accumulator is that shape, so this is one `_ubRead` per
name per iteration that no longer runs.

## `_mod` and `_rem` over floats and ratios

Both generic helpers had a `Long` fast path and a `BigInteger` fallback and NO float arm,
so a `Double` reaching them died casting `Double` to `BigInteger` -- reachable whenever
the emitter could not see the operand type (a variable rather than a literal), including
from a fused site's bail. They now begin with the same double prologue the other binary
helpers use: `mod` through `_fmod` (CL's divisor-signed float modulo, what the
double-literal emission already called), `rem` through `DREM` (the dividend's sign).

A RATIO operand was the same hole one level up -- it fell past the `Long` guard into
`_big`, which cannot cast the `BigInteger[2]` representation. Both helpers now carry the
`_ratnum`/`_ratden` prologue `_add`/`_sub`/`_mul` have (`emitRatioGuard`, so EITHER
operand being a ratio takes it): with a = an/ad and b = bn/bd the quotient a/b is
`(an*bd)/(ad*bn)`, so the integer remainder of THAT division, read over the common
denominator `ad*bd`, is the answer -- one `_rat` call, which reduces and demotes an exact
division to an integer. Denominators are positive, so the quotient's denominator carries
the DIVISOR's sign and `_mod`'s existing correction (`emitDivisorSignCorrection`, now
shared with the `BigInteger` arm) applies unchanged. ANSI defines `mod`/`rem` over any
REAL and both wasm backends already answered rationals, so the divergence was the
interpreter (whose integer check was too narrow -- `Environment.rationalRemainder` is the
matching arm) and the JVM. All four are pinned by ci-spec case `ratio-mod-rem`.

## Gates

None. Unlike the fusion and the typed loops this is not a speed-for-size trade: every
change here makes the emitted code SMALLER as well as faster (`compileUnboxedOperand`
replaces a `Double.valueOf` + `_dbl` + `checkcast` + `invokevirtual` chain with an
`ldc2_w`; `compileForEffect` deletes a call and a `pop`), so `--optimize=size` gets it
too. The double fast path inside `_fx$N` rides the fusion's own `Ctx.intFusion` gate,
since the method it lives in only exists when fusion is on.

## Numbers (2026-08-28, linux/x86-64, exec jar, GraalVM 25)

`bench-report/programs/mandelbrot.lisp` (400x400 grid, 200 iterations, no declarations),
`-o Bench.class` under `java`, best of three: **206 ms -> 100 ms**, against ABCL's 133 ms
on the same JVM -- the row's whole gap, closed, and the only benchmark ABCL had a real
lead on. `hash.lisp` 544 -> 494 ms (the `compileForEffect` half, plus the one-entry bucket
capacity of `.kb/hash-tables.md`); ABCL 659 ms. `matmul`/`fib`/`clos` unchanged -- they
were already the fastest column.

## Pinning tests

`JvmLispCompilerTest.doubleArithmeticMatchesTheInterpreterOnBothOptimizeLevels` (literal
widening, `-0.0`, the NaN comparison rule, a mixed Long/Double tree that bails off BOTH
fast paths, a bignum leaf, `mod`/`rem` over floats, the float-initialised accumulator, the
statement-position `setq` shapes -- at `DEFAULT` and at `SIZE`), and the
`double-arithmetic-unboxed-and-fused` ci-spec case, which pins the same answers across all
four backends.
