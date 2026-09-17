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
  `_min`/`_max` (throw `Expected real number`). `eql`/`equal`/`eq` need
  nothing (they fall through to the holder's `equals`).
- Gated `GROUP_COMPLEX` (`JvmComplexRuntimeBuilder`, only when
  `mayCreateComplex`: a `#C` literal, a `complex`/`conjugate` call, a `sqrt`
  mention, or a `#'complex`/`#'conjugate`/`#'phase` designator -- plus
  `mayEscapeToComplex`, the per-CALL gate below): `_ccomplex`
  (canonicalize), `_cadd`/`_csub`/`_cmul`/`_cdiv` (exact-or-float over
  `_add`/`_sub`/`_mul`/`_div`/`_dbl`/`_cmp`, so funnels match real
  arithmetic), `_cneg` (separate from `_csub`-from-zero: `0.0 - 0.0` is
  `+0.0`, `-0.0` is not), `_csqrt` (negatives root into the plane),
  `_cpow` (exact integer powers by squaring, else `exp(w*log z)`),
  `_cpowr` (two REAL operands whose answer can still be complex: a negative
  base to a non-integer power, else a delegation to the ungated `_pow`),
  `_cu1` (the 15 unary math functions by int opcode: `asinh`, `acosh`, `atanh`
  have hand-rolled real arms -- `java.lang.Math` has no inverse hyperbolic --
  and `log`/`asin`/`acos`/`acosh`/`atanh` cross a real argument outside their
  domain into the complex arm at `(x, +0.0)`, the way `cis` always answers the
  arm), `_cconjugate`,
  `_ccmpb` (throw-on-holder then delegate), `_cphase`, and the `#C(re im)`
  printer arms (parts recurse through the same renderer).
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
printer dispatch, the `_eval` self-eval arm) therefore consults the `static
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
  complex value).
- `#'complex`/`#'conjugate`/`#'sqrt`/`#'phase` wrappers are reference-gated on
  the JVM (their bodies call gated helpers; an ungated wrapper would force the
  group into every program), and so are `#'log`/`#'asin`/`#'acos`/`#'expt`
  since the real-domain escape below. `fboundp` still answers from the static
  registry.

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

## Real arguments that leave the real domain (`.todo/763`, 2026-09-11)

`log` of a negative, `asin`/`acos` beyond `[-1, 1]` and `expt` of a negative base to a
non-integer power answer the PLANE, not `NaN` -- the escape `sqrt`, `acosh` and `atanh`
already took. The value is the EXISTING complex arm run at `(x, +0.0)`
(`_cu1`'s real path branches into `emitRealAsComplex`, exactly like acosh's), so nothing
is defined twice and `(asin 2d0)` IS `(asin #c(2d0 0d0))`.

**`expt` is the one exception, and deliberately.** A real base's phase is EXACTLY pi, so
the answer is `StrictMath.pow(|x|, y)` turned through `y*pi` radians (`_cpowr`, the
interpreter's `negativeBasePow`) -- not `_cpow`'s `exp(w*log z)`, which would have to
recover that phase from a logarithm. Measured against SBCL 2.2.9 on 2026-09-11:
`(expt -8d0 1/3)` is `#C(1.0000000000000002 1.7320508075688772)` by the rotation and
`#C(1.0 1.732050807568877)` through the logarithm, and SBCL answers the FIRST for the
real base and the SECOND for `(expt #c(-8d0 0d0) 1/3)` -- it splits the same way, for the
same reason. The rotation also makes `(expt -2d0 0.5d0)`'s imaginary part exactly
`(sqrt 2)`. So `(expt x y)` and `(expt (complex x 0) y)` disagree in the last bits by
design; do not "fix" one to the other. `_cpowr` delegates every other operand pair to the
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

The answer's type is now a run-time property, and the syntactic steering around it is
unchanged, so two shapes keep the pre-existing corner rather than gaining an arm:

- `(+ 1.0 (log x))` takes the unboxed double path and lands in `_dbl`'s
  `Expected number` (catchable, correctly rendered) when `x` is negative -- the same
  corner a complex arriving through a variable has always had. Widening
  `containsComplex` to cover the escapes would fix it and would also push every
  `(* alpha (log p))` in a numeric loop off the f64 path, which is the wrong trade.
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
  "Expected real number" for a holder. `atan` NEVER opens the complex gate -- it is not
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
when `.todo/779` replaced it (below) rather than having to be re-pinned around it.

## Complex division is Smith's form (`.todo/779`, 2026-09-11)

`(a+bi)/(c+di)` in FLOATS folds on whichever divisor part is larger, never on the
`c^2+d^2` denominator:

```
|c| >= |d| :  r = d/c,  den = c + d*r,  re = (a + b*r)/den,  im = (b - a*r)/den
|c| <  |d| :  r = c/d,  den = c*r + d,  re = (a*r + b)/den,  im = (b*r - a)/den
```

Two properties, and a program notices both. A REAL divisor makes `d`, `r` and the
correction zero, so each part is ONE rounded division instead of three -- which is what
puts `(log -8d0 2d0)` on SBCL's `#C(3.0 4.532360141827194)`. And the only quantity ever
squared is the smaller part over the larger, so nothing intermediate leaves the range the
operands live in: the denominator form overflowed above `|c| ~ 1.3e154` and flushed to
zero below `~1.5e-162`, answering `#C(NaN NaN)` for `(/ #c(1d200 1d200) #c(1d200 1d200))`
and for the `1d-200` twin, both of which are `#C(1.0 0.0)`.

Measured against SBCL 2.2.9 on `linux/amd64`, 2026-09-11: this form reproduces SBCL's
answer on every finite row probed (the two range cases, `#c(3 4)`/`#c(4 3)` either side
of the fold, a pure-imaginary divisor, a real dividend, `1d300`/`1d-300` divisors) --
SBCL computes the same form. The one deliberate DIVERGENCE is the zero divisor: SBCL's
FPU traps, so `(/ #c(1d0 2d0) 0d0)` signals `DIVISION-BY-ZERO` and
`(/ #c(1d0 2d0) #c(0d0 0d0))` `FLOATING-POINT-INVALID-OPERATION`, where this runtime does
not trap and answers `#C(NaN NaN)` -- `r` is `0/0` and every part follows. That is the
value the denominator form answered too, so the existing pin did not move; a `d == 0.0`
special case would have made it `#C(Infinity Infinity)`, which claims an answer where
there is none.

Three implementations, one form, and they must agree: `Environment.smithDivide` (the
interpreter's float arm of `divComplex`), `JvmComplexRuntimeBuilder.buildDiv`'s float
tail, and `WasmComplexRuntimeBuilder.buildDivBody`'s float arm -- WASM keeps the whole
exact path on the `_rat_*` helpers below it and branches into raw `f64` instructions when
any of the four parts is a float, so its digits ARE the JVM's here (unlike the software
log core's). The EXACT (rational) path of all three is untouched and still divides by
`c^2+d^2`: rationals neither round nor overflow, and `exactDivComplex`'s zero-divisor
funnel answers where it always did. Pinning tests:
`LispEvaluatorTest#evalComplexFloatDivisionIsSmithsForm`,
`JvmLispCompilerTest#compileAndRunComplexFloatDivisionIsSmithsForm` (a differential
against the interpreter) and
`WasmLispCompilerIntegrationTest#compileAndRunComplexFloatDivisionIsSmithsForm`.

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

A complex arriving only through a variable beside a double literal takes the
unboxed path into `_dbl`'s `Expected number` landing (catchable, correctly
rendered) instead of the complex answer; ordering there answers `nil` instead
of signalling. The embedded runtime reader has no `#C` arm yet. `signum` of a
complex is 754's audit.

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
