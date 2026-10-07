# JVM complex numbers

The JVM representation of a complex value, and the gates that keep
complex-free programs byte-identical. Interpreter semantics (canonicalization,
exactness, SBCL parity) live in `.todo/751-*`; the type-system/corpus/docs leg
is `.todo/754-*`; the WASM twin is `.todo/753-*`.

## Representation: `runtime/RontoComplex`, not `Object[]`

A complex value is a `RontoComplex` holder (two `Object` fields holding the
compiled real representations: `Long`, `BigInteger`, `BigInteger[2]`,
`Double`). An `Object[2]` pair was rejected because it is structurally
identical to a cons cell and would answer `consp`/`car`/every cons-shaped
predicate wrongly; an `instanceof` keeps all of those answering with zero
exclusion arms. The holder is dumb (fields plus part-wise `equals`/`hashCode`
over the compiled shapes, which is the interpreter's `eql`); all logic lives
in generated helpers so nothing duplicates `_rat`/`_norm`/`_dbl`.

## Two helper tiers

- Unconditional numeric helpers gain holder arms that are emitted only for a
  complex-capable program: `_abs` (float modulus), `_cmpb` (part-wise numeric:
  `=` and `zerop` over holders compare, ordering never reaches it),
  `_min`/`_max` (throw a REAL operand-type report), and the generic
  `_add`/`_sub`/`_mul`/`_div`/`_neg`/`_pow`, which hand a holder to their `_c*`
  twin where the real body would reject it ("A complex through a variable"
  below). `_dbl`'s REAL arm sits after every real rung. `eql`/`equal`/`eq` need
  nothing (they fall through to the holder's `equals`).
- A product keeps a `-0.0` part the way SBCL does (measured 2026-10-06, SBCL 2.2.9: the
  interpreter lost it from its `(1, 0)` seed and the JVM and both WASM backends from
  treating a real operand as `(r, 0)`): a REAL operand multiplies each part of the other
  (`(* #c(0.0 -0.0) 1)` is `#C(0.0 -0.0)`), two complexes take `(ac-bd, ad+bc)`, the
  fold is left to right from the first operand, and a step whose parts are all exact stays
  exact whatever float follows. `_cmul`, `_c_mul` and `Environment.mulComplexPair` are one
  rule; `ComplexProductSignedZeroFixture` pins it.
- A complex base to a RATIONAL power is SBCL's polar form, `(* (expt (abs z) p) (cis (* p
  (phase z))))`, except an exact base to an int-range integer (squaring, exact). Measured
  2026-10-06, SBCL 2.2.9 with `*read-default-float-format*` `double-float`: SBCL does NOT
  multiply for an integer power -- `(expt #c(1.1d0 2.2d0) 1)` is `#C(1.1000000000000003 2.2)`,
  `... 3` is `#C(-14.641000000000007 -2.661999999999997)` where the product is
  `#C(-14.641000000000005 -2.662000000000001)` -- and every complex-float/ratio row tried
  matched the polar form digit for digit, signs included (`(expt #c(0.0 -0.0) 2)` is
  `#C(0.0 -0.0)`, `(expt #c(1.5 -0.0) -3)` `#C(0.2962962962962963 0.0)`, `(expt #c(-0.0 -0.0)
  3)` `#C(-0.0 -0.0)`). Until that day all four backends took `exp(w*log z)` there: `-0.0`
  parts came out `+0.0` and `(expt #c(1.1 2.2) 2)` was `#C(-3.630000000000001 ...)` against
  SBCL's `-3.6300000000000003`. A float or complex power keeps `exp(w*log z)` (SBCL's own
  dispatch), and ANY zero power answers `#C(1.0 0.0)` (SBCL's `(1+ (* base power))`; the
  formula left `(expt #c(0.1 -0.2) 0)` at `#C(1.0 -0.0)`). The form runs on fdlibm `pow`,
  so a row whose `pow` is not correctly rounded sits a ulp from SBCL's glibc one:
  `(expt #c(1.0 2.0) 3)` is `#C(-11.000000000000002 -1.9999999999999971)` here and
  `#C(-11.000000000000004 -1.9999999999999973)` in SBCL (`pow(sqrt 5, 3)`; the old formula
  happened to land on SBCL's digits). `Environment.exptComplex`/`polar`, `_cpow`'s polar arm
  (`emitPolarHolder`, shared with `_cpowr`) and `WasmComplexCompiler.emitExptFloat`
  (`emitPolarOf`, shared with the real escape) are one rule; `ComplexRationalPowerFixture`
  pins it.
- Gated `GROUP_COMPLEX` (`JvmComplexRuntimeBuilder`, only when
  `mayCreateComplex`: a `#C` literal, a `complex`/`conjugate` call, a `sqrt`
  mention, or a `#'complex`/`#'conjugate`/`#'phase` designator -- plus
  `mayEscapeToComplex`, the per-CALL gate below): `_ccomplex`
  (canonicalize), `_cadd`/`_csub`/`_cmul`/`_cdiv` (exact-or-float over
  `_add`/`_sub`/`_mul`/`_div`/`_dbl`/`_cmp`, so funnels match real
  arithmetic), `_cneg` (separate from `_csub`-from-zero: `0.0 - 0.0` is
  `+0.0`, `-0.0` is not), `_csqrt` (negatives root into the plane),
  `_cpow` (exact integer powers by squaring, a zero power `#C(1.0 0.0)`, a rational power
  over a holder the polar form, else `exp(w*log z)`),
  `_cpowr` (two REAL operands whose answer can still be complex: a negative
  base to a non-integer power, else a delegation to the ungated `_pow`),
  `_cu1` (the 15 unary math functions by int opcode: `asinh`, `acosh`, `atanh`
  have hand-rolled real arms -- `java.lang.Math` has no inverse hyperbolic --
  and `log`/`asin`/`acos`/`acosh`/`atanh` cross a real argument outside their
  domain into the complex arm at `(x, +0.0)`, the way `cis` always answers the
  arm), `_cconjugate`,
  `_ccmpb` (throw-on-holder then delegate; every ordering of the program calls
  it), `_cphase`, and the `#C(re im)` printer arms (parts recurse through the
  same renderer).
  The holder class file travels exactly then (`needsComplexRuntime`).

The hard rule: no `am/ik/rontolisp/runtime/RontoComplex` reference -- class
constant, field, `instanceof`, helper body -- exists in a class whose gate is
off. The constant pool is walked by name (`projectReferences`), so even an
unused entry breaks the travelling closure; and `instanceof` resolves its
class on first execution, so a stray test would `NoClassDefFoundError` an
otherwise single-file artifact the first time it prints. References are
created only under the gate (nullable holder refs, `ctx.usesComplex`
emissions, nullable print refs); helper calls use on-demand refs plus the
force-on retry.

## The holder-presence probe (`.todo/757`, 2026-09-10)

The gate over-approximates: dead `sqrt` arms in an unpruned splice keep it
open, and callers the dispatchers keep alive defeat a reachability re-check,
so a gate-on class can still run where its `RontoComplex.class` file is
absent. Every holder TEST (the `numberp`/`complexp`/`realpart`/`imagpart`
inline shapes, the `_cmpb`/`_abs`/`_signum`/`_min`/`_max`/`_dbl` arms, the
`_add`/`_sub`/`_mul`/`_div`/`_neg`/`_pow` arms, the unary math sites' test,
`_ccmpb` and `_cpowr` -- the two gated helpers a program reaches without building
a holder -- the printer dispatch, the `_eval` self-eval arm) therefore consults the `static
final boolean _hasComplex` probe first -- set once in `<clinit>` by a
`Class.forName` that catches `ClassNotFoundException` -- and takes its
holder-less shape when the class did not load. Exact, not heuristic: no
holder instance can exist without its class. Only the constructor paths (the
`_c*` helpers) keep hard links: building a complex without its class is a
genuinely missing file. Pinning test:
`JvmLispCompilerTest#aComplexFreeArangeProgramRunsStandaloneWithoutTheHolder`
(the lone-class run; the device-gated twin is
`JvmLinalgGpuAccelCompilerTest#aLazyResultAllocatesNoHostArrayOnTheCompiledBackend`).

## Call-site rules (`JvmComplexCompiler`, per-op gates)

- `complex`/`conjugate`/`sqrt`/`phase` always call their helper (`sqrt`
  unconditionally: negativity is a runtime property, and `NaN`-vs-plane is a
  wrong number, not an error; `phase`/`conjugate` the same way, so first-class
  references work through their wrappers). `complexp` is constant nil,
  `realp`/`realpart`/`imagpart`/`numberp` take the holder-less shape when the
  program cannot build a holder -- no holder can exist then, and the class
  stays out of the constant pool either way. Literals (code position and
  `quote`) emit parts plus `_ccomplex`.
- `hasComplexOperand` (a literal or `complex`/`conjugate` form in the tree)
  steers `+ - * /` to the `_c*` fold, `abs`/`expt`/unary-math off the double
  path, and `=`/`zerop`/`1+`/`1-` (which expand to them) with it; ordering
  uses `_ccmpb`. `min`/`max` need no gate (`isDefinitelyDouble` never fires
  on complex). Int-fusion, typed loops and raw stores decline a tree
  containing complex (the fused bail would answer `_add`'s error, not the
  complex value). A complex the tree does not show is the run-time arms' job
  (next section).
- `#'complex`/`#'conjugate`/`#'sqrt`/`#'phase` wrappers are reference-gated on
  the JVM (their bodies call gated helpers; an ungated wrapper would force the
  group into every program), and so are `#'log`/`#'asin`/`#'acos`/`#'expt`
  since the real-domain escape below. `fboundp` still answers from the static
  registry.

## A complex through a variable (2026-10-06)

The steering above is syntactic, so a complex reaching an operator through a parameter, a
global, a list element or a designator's argument -- nothing complex in the call's own
text -- landed in the real-only body: `(defun m (a b) (* a b)) (m #c(1 1) 2)` reported
`*: The value #C(1 1) is not of type NUMBER`, `exp`/`sin`/`expt` through a parameter the
same, and `(< z 1)` answered `nil` (`_cmpb`'s holder arm is `=`'s part-wise test). In a
program `compiler/ComplexCapability` gates -- the `usesComplex` scan, one predicate with
the WASM backend's -- the generic entries find the holder at run time. The rule: **an arm
sits where the real body would REJECT a holder, never on a path a real takes**, so every
complex-free class is byte-identical and the Long fast path tests nothing new.

- `_add`/`_sub`/`_mul`/`_div` SPLIT in such a program (`HeadAndTail`): the helper keeps the
  `Double` pair (read straight out of the boxes) and, but for `_div`, the `Long` pair, and
  hands every other pair -- mixed float, ratio, bignum, an overflow, a holder -- to its
  tail `_addx`/`_subx`/`_mulx`/`_divx`, the one-piece body plus the holder arms
  (`HolderArms`): beside a `Double` in the prologue (once one side is known to be a
  `Double`, the other is tested), at the rational path's head and ahead of `_big`'s funnel
  (for `_divx`, the exact path's head), each a delegation to `_cadd`/`_csub`/`_cmul`/
  `_cdiv`. **The arms must not sit in the helper itself**: measured 2026-10-06 on an n-body
  (a `sqrt` gates it, and a complex can reach its float arithmetic through the body
  vectors), the arms inside `_add` & co. (143 -> 218 bytecodes) DOUBLED its allocation under
  Graal -- 111 -> 228 young collections with an 8 MB young generation, 160 -> ~450 ms --
  and arms on the cold paths alone still 164; the split head (74 bytecodes) is back at 110
  and at the base time. C2, which eliminates none of those boxes, ran both alike. `_neg`
  keeps its arm ahead of `_big`, to `_cneg` -- never `_csub` from zero, which turns a
  `-0.0` part into `+0.0`. `_pow` on each of its three arms ahead of `_dbl`/`_ratnum`, to
  `_cpow`; `_cpowr`, which a site with a variable operand reaches, at its head. `_cdiv`'s
  no-holder delegation back to `_div` cannot loop: each side delegates on the other
  answer.
- `_dbl`'s REAL arm moved from its first instruction to after the `Double`, ratio and
  `Number` rungs -- the same answer, and no holder test on the float path.
- A unary math site (`exp sin cos tan atan sinh cosh tanh`; `log`/`asin`/`acos` with a
  variable already reach `_cu1` through the escape gate) whose argument may hold a
  complex evaluates it through `JvmFloatOperands` and tests it: a holder takes `_cu1`, a
  real the inline `StrictMath` call (next section).
- Every ordering of such a program calls `_ccmpb` (`JvmComparisonCompiler.orderingOrEquality`,
  and the fused compare's bail), `=` keeps `_cmpb`.
- Every holder test is behind the presence probe: `_ccmpb` and `_cpowr` gained it, since
  they now run in programs that never build a holder.

An operation whose own form spells a float literal (`(+ z 1.5)`, `(exp (* 1.0 z))`) is
the next section's.

Measured 2026-10-06 (linux/amd64, GraalVM 25): the size-report and bench-report programs
compile to the same JVM class and jar bytes (and, for the WASM twin, the same modules at
every level). A gated program pays for the twins its helpers now reach, which the JVM's
name-reachability shake keeps whether or not a complex can flow there -- measured with
the arms still inside the helpers, before the split added its four small heads: `+`
alone +1.3 KB class / +0.7 KB jar, all four operators +2.8 / +1.0 KB, `exp` or `sin`
(`_cu1`) +4.7 / +1.5 KB, `expt` (`_cpow`) +5.0 / +1.9 KB;
`examples/ml/linear-regression.lisp` +3.5 / +1.7 KB. The generic float, ordering, `=`,
unary and `expt` loops of such a program ran unchanged within noise.

Pinned by `ComplexThroughAVariableFixture` (`LispEvaluatorTest#complexThroughAVariable`,
`JvmLispCompilerTest#compileAndRunComplexThroughAVariable`,
`WasmLispCompilerIntegrationTest#complexThroughAVariable`: the answers outside EH mode, the
REAL signals and the reported culprit under handlers) and `ci-spec.yaml`'s
`complex-arithmetic-through-a-variable`.

## A complex beside a float literal

A float literal routes `(* 2.0 z)`, `(exp (* 1.0 z))` and `(= (* 2.0 z) 1.0)` onto the
unboxed double path (`hasDoubleLiteral`, `isDefinitelyDouble`), whose `_dbl` signals REAL
for a holder, so the variable's complex never met the helpers above. In a program
`ComplexCapability` gates, a site whose operands may hold a complex
(`ComplexCapability.mayYieldComplex`: a variable or a call, or an arithmetic operation
over one; never a constant or a declared float) takes `JvmFloatOperands`; every other
site, and every complex-free class, keeps its raw emission byte for byte.

- Each operation evaluates its operands as the interpreter does: left to right into
  temporaries, an inner float-literal operation applied where it stands. Then it tests
  the operands a variable or a call produced (`instanceof RontoComplex` behind the
  presence probe) and the inner operations that went generic, and folds raw when none
  holds a complex, through the generic helpers (`_add` & co., whose tails hand a holder
  to `_cadd` & co.; `_neg` for a unary minus) when one does.
- An inner operation leaves its double in one slot and, only when it went generic, its
  boxed answer in another (null otherwise): the raw path boxes nothing it did not box
  before, and the parent reads whichever holds the value. It runs under its own
  operator and source site, so a wrong-typed operand of an inner `*` reports `*`.
- Order: every operand of an operation runs before the operation signals, and an inner
  operation signals before the outer one's later operands run -- the interpreter's
  order. The complex-free raw path converts each operand before the next one runs, and
  int fusion evaluates every leaf of its tree before any operation, so neither keeps it.
- The consumers do the same over their operands (`JvmFloatOperands.compileCall` for the
  one-helper shapes): the comparison on proven doubles (`_cmpb` for `=`, `_ccmpb` for an
  ordering, which reports the complex the inner operation computed), `min`/`max`
  (`_min`/`_max`), the unary math functions (`_cu1`), `atan`'s two-argument form (`_dbl`
  over the boxed operands, the same REAL report), `abs` (`_abs`), `expt` (`_pow`) and
  `signum` (`_signum`). The rounding family and `random` take their argument boxed, so
  their `_dbl` already meets the computed complex. `isDefinitelyDouble` turns down a tree
  with a complex literal, as its WASM twin does, so `(max (+ #c(1 2) 0.5) 1.0)` reports
  `#C(1.5 2.0)` through `_max`, not the literal through `_dbl`.
- The generic fold is pairwise, like SBCL's, the generic sites' and (since 2026-10-07)
  the interpreter's: `(+ z (- z) 1.5)` over an exact `z` is `1.5` everywhere ("Complex
  division is SBCL's dispatch").

Pinned by `ComplexBesideAFloatLiteralFixture` (`LispEvaluatorTest#complexBesideAFloatLiteral`,
`JvmLispCompilerTest#compileAndRunComplexBesideAFloatLiteral`,
`WasmLispCompilerIntegrationTest#complexBesideAFloatLiteral`: the answers outside EH mode;
the REAL reports, the operator an inner operation reports under and the order under
handlers) and `ci-spec.yaml`'s `complex-beside-a-float-literal`.

## The asin/acos branch cut, and their exact real axis (`.todo/764`, 2026-09-11)

`asin` and `acos` cut the real axis outside `[-1, 1]`, and the value ON the cut
is the one CLHS names: continuous with quadrant IV above `+1`, quadrant II
below `-1`. **The side is decided by the sign of the REAL part; the sign of an
imaginary ZERO is discarded** -- `(asin #c(2d0 0d0))` and `(asin #c(2d0 -0d0))`
are ONE value, `#C(1.5707963267948966 -1.3169578969248166)`, SBCL's for both.
That is the opposite of `sqrt`, `log` and `acosh`, where the imaginary zero's
sign picks the sheet (`(sqrt #c(-1d0 -0d0))` is `#C(0.0 -1.0)`), and the split
is deliberate: SBCL and CLHS agree here, and a program that means one side of
an asin cut has to say which side anyway.

All three implementations (`Environment.complexAsin`/`complexAcos`, `_cu1`'s
`U1_ASIN`/`U1_ACOS`, `WasmComplexCompiler.emitComplexAsinInto`/`...Acos...`)
run Kahan's form over the two roots `u = sqrt(1 - z)` and `v = sqrt(1 + z)`:

```
asin z = (atan2(re, Re(u*v)),    asinh(Im(conj(u)*v)))
acos z = (2*atan2(Re(u), Re(v)), asinh(Im(conj(v)*u)))
```

Both properties fall out of it, which is why no arm special-cases the axis:

- **The cut**: the imaginary parts of `1 - z` and `1 + z` are computed as
  `0.0 - im` and `0.0 + im`, and IEEE makes BOTH `+0.0` for either signed zero,
  so `u` and `v` stay on one sheet whatever sign the argument's zero had.
- **The real axis**: a real argument inside `[-1, 1]` leaves both roots real,
  so asinh's argument is a difference of zeros and the imaginary part is
  EXACTLY `0.0`. Deriving acos as `pi/2 - asin z` would lose both -- it
  answered `#C(1.0471975511965976 -1.1102230246251565e-16)` for
  `(acos (complex 0.5d0 0d0))` where SBCL (and this) answer
  `#C(1.0471975511965979 0.0)`.
- **Accuracy**: `asin z` and `asinh(i*z)` now agree to the BIT, so
  `(asin #c(1 1))`'s imaginary part IS `(asinh #c(1 1))`'s real part and
  `(sin (asin #c(0d0 1d0)))` is exactly `#C(0.0 1.0)`. The
  `-i*log(i*z + sqrt(1 - z^2))` form this replaced was 2 ulp off there.

`asinh`'s real arm is the imaginary part of every one of those, so its grouping
is their accuracy: the large branch is ONE log over `|x| + hypot(|x|, 1)`, not
`log |x| + log(1 + hypot(1/|x|, 1))`, whose two roundings land a ulp high on 18%
of the arguments above 1 (measured 2026-09-11 against 60-digit BigDecimal; it is
what kept `(asin #c(-4d0 0d0))` a ulp off SBCL). Only the SUM can overflow, so
the huge rung is acosh's, at the same `8.5e307`; `(asinh 1d0)` and `(asinh 2d0)`
are unmoved by the regrouping.

Pinning tests: `LispEvaluatorTest#evalComplexAsinAcosOnTheBranchCut`,
`#evalComplexAsinAcosOfARealArgumentAnswerAnExactZero` and
`#evalComplexAsinAcosRoundTrip`, mirrored by
`JvmLispCompilerTest#compileAndRunComplexAsinAcos*` and
`WasmLispCompilerIntegrationTest#compileAndRunComplexAsinAcos*`; the
four-backend leg is `ci-spec.yaml`'s `complex-asin-acos-branch-cut`, which pins
the CONTRACT (one value for both zero signs, the cut's sign, the exact zeros)
rather than digits the backends round differently.

## `_cu1`'s shared frame, and the differential over all fifteen arms (`.todo/765`, 2026-09-11)

The fifteen unary arms are one method over one frame: the operand's parts in
slots 2 and 4, scratch in 6, 8, 10, 12 and 14. Nothing separates the arms, so an
arm that writes a second quantity over a slot it still needs does not fail --
it answers a **plausible wrong number**. `tan`/`tanh` did, for as long as they
existed: `|cos z|^2` was stored over `cos z`'s real part in slot 14, turning the
quotient into `(s.re*|c|^2 + s.im*c.im)/|c|^2`, which on the real axis is the
NUMERATOR. `(tan #c(1d0 0d0))` answered `sin 1` and `(tanh #c(1d0 0d0))`
`sinh 1` -- values a digit-string test reads as "some transcendental". `|c|^2`
now lives in slot 10, and the arm's comment names all five live quantities and
their slots.

Which arms were wrong was measured, not assumed (2026-09-11, `linux/amd64`):
all fifteen functions at seven points -- `#c(1 1)`, `#c(-1.5 0.25)`,
`#c(0.5 -2)`, the two axes `#c(0 1)`/`#c(1 0)` and the two reals off the cut
`#c(-4 0)`/`#c(2 0)` -- compiled and compared to the interpreter line for line.
Only `tan` and `tanh` differed, on every one of their seven points; the other
thirteen were byte-identical, `acos` included (`.todo/764` had replaced its
body wholesale with the Kahan form, which fixed the slot swap the sweep
originally found there). That census is now the pinning test
`JvmLispCompilerTest#compileAndRunComplexUnaryMathMirrorsTheInterpreterArmForArm`:
it runs the generated program through `LispEvaluator` and asserts the compiled
output IS the interpreter's. A differential only sees disagreement, so both ends
carry an anchor against the real functions
(`LispEvaluatorTest#evalComplexTanTanhAreQuotientsOnEveryAxis`, and the
four-backend leg `ci-spec.yaml`'s `complex-tan-tanh-are-quotients`, which pins
the identity rather than digits the backends round differently).

`_cpow`'s float path decides a zero base to a float or complex power before `exp(w*log z)` (`emitZeroBasePow`; the rule and
its SBCL table: `.kb/error-handling.md`, "A zero base on the complex `expt` path").

## Real arguments that leave the real domain (`.todo/763`, 2026-09-11)

`log` of a negative, `asin`/`acos` beyond `[-1, 1]` and `expt` of a negative base to a
non-integer power answer the PLANE, not `NaN` -- the escape `sqrt`, `acosh` and `atanh`
already took. The value is the EXISTING complex arm run at `(x, +0.0)`
(`_cu1`'s real path branches into `emitRealAsComplex`, exactly like acosh's), so nothing
is defined twice and `(asin 2d0)` IS `(asin #c(2d0 0d0))`.

**`expt` is the one exception, and deliberately.** A real base's phase is EXACTLY pi, so
the answer is `StrictMath.pow(|x|, y)` turned through `y*pi` radians (`_cpowr`, the
interpreter's `negativeBasePow`) -- not `exp(w*log z)`, which would have to recover that
phase from a logarithm. For a RATIONAL `y` that is the complex base's polar form over
`(x, 0)` (`atan2(0, x)` is pi), so `(expt -8d0 1/3)` and `(expt #c(-8d0 0d0) 1/3)` are both
`#C(1.0000000000000002 1.7320508075688772)` -- SBCL 2.2.9's answer for both, re-measured
2026-10-06 (an entry here from 2026-09-11 said SBCL answered the complex base
`#C(1.0 1.732050807568877)`; that is its answer for the FLOAT power `(/ 1d0 3)` only). For a
FLOAT `y` the complex base takes `exp(w*log z)` and the two disagree in the last bits,
which is SBCL's split too: `(expt -8d0 (/ 1d0 3))` is `#C(1.0000000000000002 ...)` and
`(expt #c(-8d0 0d0) (/ 1d0 3))` `#C(1.0 1.732050807568877)`. The rotation also makes
`(expt -2d0 0.5d0)`'s imaginary part exactly `(sqrt 2)`. Do not "fix" the float-power split.
`_cpowr` delegates every other operand pair to the
ungated `_pow`, so the exact rational path and its error funnels stay unduplicated.

A NaN is outside every one of these domains under a naive comparison, and CL answers the
NaN back -- so each test picks the DCMP variant a NaN fails (`dcmpl` for `>`, `dcmpg` for
`<`). The acosh and atanh arms had them the other way round since they were written and
escaped into the plane for a NaN; fixed with this, pinned by
`JvmLispCompilerTest#compileAndRunRealDomainEscapesKeepANaNArgumentReal`.

### The gate: the CALL, not the mention

These four are far too common to gate on a mention the way `sqrt` is (`(expt n 2)` is in
every numeric program), and they do not need to be: their complex arm is reachable only
for SOME arguments. `LispMacroExpander.escapesToComplex(head, call)` answers whether a
single call can escape -- a non-negative literal argument to `log`, a literal inside
`[-1, 1]` to `asin`/`acos`, a literal INTEGER exponent or non-negative literal base to
`expt` all close it -- and `mayEscapeToComplex` is that predicate over the program, ORed
into `usesComplex`. **The call sites steer on the SAME predicate**, so the scan and the
emission never disagree; a disagreement that under-predicts costs a whole extra compile
pass through `GateUnderpredicted`. `(expt n 2)`, `(expt 10.0 n)`, `(log 2)`, `(asin 1)`
keep the gate shut and the single-file output
(`JvmLispCompilerTest#aLiteralProvenRealDomainKeepsTheComplexGateShut`).

The trap this walked into first: the injected `#'log` / `#'asin` / `#'acos` / `#'expt`
WRAPPERS take their argument from a parameter, which no literal can prove real, so an
ungated wrapper opened the gate for EVERY program -- `(print 1)` included, through the
`GateUnderpredicted` retry. They join `#'sqrt`'s reference-gated list in `JvmLispCompiler`
(the `List.of(COMPLEX, CONJUGATE, SQRT, PHASE, ...)` loop). A wrapper whose body reaches a
gated helper must be on that list.

### What this does NOT reach

The answer's type is now a run-time property. A float-literal operation over the call --
`(+ 1.0 (log x))`, `(* alpha (log p))` -- reaches it: the call is an operand that may hold
a complex, which the site tests while keeping its f64 path for a real ("A complex beside
a float literal" above). Widening `containsComplex` to cover the escapes instead would
have pushed every such operation off the f64 path. One shape keeps the pre-existing
corner:

- A typed numeric loop (`JvmTypedLoopCompiler`) computes `log`/`asin`/`acos` as raw f64
  and keeps the NaN. `sqrt` is in that same list with the same property and has been
  since typed loops existed: the loop's result goes into a packed float array, which has
  no complex representation anyway.

## The two-argument `atan` and `log` (`.todo/762`, 2026-09-11)

`(atan y x)` is C's `atan2` and `(log n base)` the quotient of the two logarithms
(CLHS). Both live in `JvmMathFnCompiler.compileBinary`, AHEAD of the one-argument path,
which is byte-for-byte what it was.

- **`atan2` is `StrictMath.atan2`, the same call `phase` makes**, so
  `(atan (imagpart z) (realpart z))` IS `(phase z)` and no second quadrant assembly
  exists to drift. Both arguments must be REAL (CLHS): they go through
  `compileUnboxedOperand`, whose `_dbl` funnel already throws the interpreter's
  a REAL operand-type report for a holder. `atan` NEVER opens the complex gate -- it is not
  a real-domain escape, and the two-argument form cannot answer a complex.
- **`log/2` is TWO logarithms and one division.** `escapesToComplex` therefore reads
  BOTH literals: `(log 8 2)` keeps the gate shut and compiles to two `StrictMath.log` calls
  and a `DDIV`; anything a literal cannot prove non-negative runs both arguments through
  `_cu1`'s `U1_LOG` and divides with `_cdiv`
  (`JvmLispCompilerTest#aLiteralProvenRealBaseKeepsTheComplexGateShut`).
- **`_cdiv` gained the arm that makes that quotient total**: neither operand a holder ->
  delegate to the ungated `_div`. A complex-capable site only knows at RUN time whether
  it holds a complex, and `(log 8d0 2d0)` through the gated spelling must still answer
  the REAL `3.0`, not `#C(2.9999999999999996 0.0)` (which is what the float path's
  `emitNewHolderFromSlots` built -- it cannot canonicalize a float zero away, where the
  WASM twin's `_c_complex` could, so the two backends disagreed on an arm neither could
  reach before). It is `_cpowr`'s shape: the gated helper answers the real case by
  delegating rather than by duplicating. The WASM twin (`_c_div`'s own head, to
  `_rat_div`) is the same arm.
- `#'log` was already reference-gated; `#'atan` is not and must not become so. Both
  wrappers are now `unaryOptionalSecond` (the presence dispatch is exact: neither an
  omitted base nor an omitted `x` can be spelled `nil`).

The pin here is the identity `(log n b)` = `(/ (log n) (log b))`, which holds on every
backend. It was chosen because one row of `.todo/762`'s acceptance table did NOT land
when it was written -- `(log -8d0 2d0)` was `#C(2.9999999999999996 4.532360141827194)`
against SBCL's `#C(3.0 4.532360141827194)` -- and the cause had no logarithm in it: the
complex DIVISION was the naive denominator form. The identity improved WITH the division
when `.todo/779` replaced it (below, since replaced again by SBCL's own dispatch) rather
than having to be re-pinned around it.

## Complex division is SBCL's dispatch (2026-10-07; Smith's form 2026-09-11)

`+ - /` with a complex operand fold left to right ONE PAIR at a time on every backend, and
each `/` pair is SBCL 2.2.9's `two-arg-/`:

```
complex / real     :  (re/y, im/y)                 each part through the real /
x / (c+di), |c|>|d|:  r = d/c,  dn = c*(1 + r*r)   (|c| <= |d|: r = c/d, dn = d*(1 + r*r))
  complex x=(a+bi) :  re = (a + b*r)/dn,  im = (b - a*r)/dn     (mirrored: (a*r + b)/dn, (b*r - a)/dn)
  real x           :  re = x/dn,          im = -(x*r)/dn        (mirrored: (x*r)/dn,     -x/dn)
```

Every operation is a real step with SBCL's contagion: an exact operand converts only where
it meets a float. So an EXACT divisor's `r` and `dn` stay exact beside a float dividend,
an exact zero dividend negates to an exact zero (`(/ 0 #c(1.0 -2.0))` is `#C(0.0 -0.0)`),
and an exact step stays exact whatever float follows (`(/ z z 1.5)` over an exact `z` is
`0.6666666666666666`, `(/ z 0 1.5)` signals `division-by-zero`). The comparison is
STRICT: a tie takes the mirrored arm (`(/ #c(1.0 1.0) #c(1.0 -1.0))` is `#C(-0.0 1.0)`).
Only the smaller part over the larger is ever squared, so nothing intermediate leaves the
operands' range (`(/ #c(1d200 1d200) #c(1d200 1d200))` is `#C(1.0 0.0)`; the `c^2+d^2`
denominator answered `#C(NaN NaN)`), and a real divisor is one rounding per part
(`(log -8d0 2d0)` is SBCL's `#C(3.0 4.532360141827194)`).

Measured against SBCL 2.2.9 (`*read-default-float-format*` `double-float`, linux/amd64),
2026-10-07, overturning 2026-09-11's "SBCL computes the same form" (Smith's
`dn = c + d*r`, `>=`, and every operand floated first when any part was a float), which
had held on the nine rows probed then: 400 random float-real / float-complex quotients
matched this real-dividend form 400/400 and the old form 265/400; 500 random
float-complex / float-complex matched `dn = c*(1+r^2)` 500/500 and the old `c + d*r`
354/500; float dividends over EXACT divisors matched the exact-`r`/`dn` reading 300/300
(real) and 300/300 (complex), against 204 and 282 for floating first. Signed zeros moved
too: the old form answered `#C(-0.0 0.0)` for `(/ #c(-0.0 -0.0) 1.0)` (SBCL
`#C(-0.0 -0.0)`) and `#C(1.0 0.0)` for `(/ 1 #c(1.0 0.0))` (SBCL `#C(1.0 -0.0)`). A random
400-form corpus of `+ - /` folds over every operand shape (exact, ratio, float, signed
zeros, exact and float complexes) then matched SBCL on every row SBCL does not trap on,
and all four backends agreed on all 400.

`+` and `-` needed no new form: SBCL counts a real beside a complex as `(r, 0)` with an
EXACT zero, and contagion makes that the same IEEE sum as a float zero
(`(+ #c(0.0 -0.0) 1)` is `#C(1.0 0.0)` there too). What moved was the interpreter alone,
whose `addComplex`/`subComplex`/`divComplex` floated EVERY argument as soon as one was a
float: `(+ z (- z) 1.5)` was `#C(1.5 0.0)` where SBCL and the compiled backends answer
`1.5`.

The zero divisor is still the deliberate divergence, in a new shape: SBCL's FPU traps, so
`(/ #c(1d0 2d0) 0d0)` signals `DIVISION-BY-ZERO` and a zero complex divisor
`FLOATING-POINT-INVALID-OPERATION`. This runtime does not trap. A zero REAL divisor now
divides each part, so the parts are what the real `/` answers here (`#C(Infinity
Infinity)`, `#C(Infinity NaN)` for a zero part) -- the old form answered `#C(NaN NaN)`
from `r = 0/0`; a zero COMPLEX divisor still makes `r` `0/0` and answers `#C(NaN NaN)`.

Three implementations, one rule, and they must agree: `Environment.divComplexPair` (every
step a `real*Step`), `JvmComplexRuntimeBuilder.buildDiv` and
`WasmComplexRuntimeBuilder.buildDivBody`. The compiled two keep a raw-double arm for a
complex over a FLOAT complex (every operation meets a float there, so floating first IS
the contagion) and run everything else -- a real dividend, an exact divisor -- through the
generic helpers (`_add` & co. / `_rat_*`), whose contagion is the interpreter's. Pinning
tests: `ComplexSumQuotientFixture` (`LispEvaluatorTest#complexSumQuotient`,
`JvmLispCompilerTest#compileAndRunComplexSumQuotient`,
`WasmLispCompilerIntegrationTest#complexSumQuotient`: SBCL's rows, signed zeros and last
digits), `LispEvaluatorTest#evalComplexFloatDivisionIsSbclsForm`,
`JvmLispCompilerTest#compileAndRunComplexFloatDivisionIsSbclsForm` (a differential against
the interpreter) and `WasmLispCompilerIntegrationTest#compileAndRunComplexFloatDivisionIsSbclsForm`.

The WASM leg of that test found a defect of its own, in the SHARED front end:
`compiler/DoubleValuedForms.certainlyDouble` scanned an operator's arguments in one pass
and answered true at the first literal double, so `(+ 3d0 #c(1d0 2d0))` was "certainly a
double" -- it never reached the complex. The JVM ignores the predicate, but
`WasmPrintCompiler` uses it to skip the value dispatch, and its `ref.cast` to
`TYPE_FLOAT` TRAPPED the module (a hard wasmtime "cast failure", not a catchable error)
for any float literal standing LEFT of a complex. The complex scan is now a pass of its
own, ahead of the double scan;
`WasmLispCompilerIntegrationTest#staticallyTypedPrintArgumentsPrintWhatTheValueDispatchWouldHave`
carries the four shapes.

## Known corners (documented, not fixed here)

The embedded runtime reader has no `#C` arm yet. `signum` of a complex is 754's
audit.

The `_cu1` real path and the interpreter's unary math are both
`StrictMath.<fn>` (fdlibm, `.kb/transcendentals.md`), one number on every
platform. They were `Math.<fn>` until 2026-09-17, which is not: `Math.exp(1.0)`
is `2.718281828459045` on x64 and `2.7182818284590455` on aarch64 (the defect
behind the deleted `.todo/756`, `./mvnw test` red on every aarch64 box), and
`Math.log(3.0)` is `1.0986122886681098` on x64 and `...096` on aarch64, which
reached the COMPLEX answers through `complexLog`'s `log(hypot(...))`
(`(atanh 2)` `0.5493061443340549` / `...548`). The pinning tests still spell the
call (`"#C(" + StrictMath.log(3.0) / 2 + " ...)"`) rather than a digit string,
and the round trips (`(cosh (acosh #c(1 1)))`) assert closeness to the argument
within 2 ulps, which is what a round trip actually pins; either shape is now
platform-independent.

Pinning tests: `JvmLispCompilerTest#compileAndRunComplex*` (mirrors
`LispEvaluatorTest`'s `evalComplex*` case for case);
`JvmRuntimeClassFilesTest` covers the holder's travelling list.
