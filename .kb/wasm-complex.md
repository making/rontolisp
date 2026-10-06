# WASM complex numbers

The WASM GC representation of a complex value, and the gates that keep
complex-free programs working. Interpreter semantics (canonicalization,
exactness, SBCL parity) live in `.todo/751-*`; the JVM twin is
`.kb/jvm-complex.md`; the type-system/corpus/docs leg is `.todo/754-*`.

## Representation: `TYPE_COMPLEX` is a tagged 3-field struct

A complex value is `struct {i32 tag, (ref null eq) re, (ref null eq) im}`
(`WasmLispCompiler.TYPE_COMPLEX`), own rec group, tag always 0. The tag is
structural ballast, not data: the natural two-eqref shape is `TYPE_FARRAY`'s
twin, and **wasmtime canonicalizes structurally identical types together** --
measured 2026-09-09: two `{(ref null eq), (ref null eq)}` structs in different
rec groups answer `ref.test` true for each other's values, so a packed array
printed as `#C(` and died on the field cast. An own rec group alone does NOT
save a twin shape; the `{i32, eqref, eqref}` shape has no twin anywhere in the
module (full inventory taken the same day -- the only other multi-field shapes
are `{mut,mut}` cons, `{i32,i32}` ratio, `{i32,eqref}` closure and the 5-field
string). A future struct must not take `{i32, eqref, eqref}` either.

Index bookkeeping: the type sits after `BIGINT_TYPE_LAST` (every later `TYPE_*`
comment number moved by one); the seven functions (`FUNC_C_COMPLEX`,
`FUNC_C_ADD/SUB/MUL/DIV/NEG`, `FUNC_TYPE_ERR_REAL`) append after the last fixed
helper (`FUNC_FUN_NAME`), so no function index above shifts. The helpers are
emitted unconditionally, like the ratio block -- the limb-block precedent
(`.kb/wasm-bignum.md`): anything can overflow into them at run time, and
`--optimize` shakes what a program never calls.

## Two emission tiers

- Runtime (`WasmComplexRuntimeBuilder`, always present): `_ccomplex`
  (canonicalize: nested-complex/non-real parts land in `_type_err_num`, float
  anywhere coerces both parts through the shared `_as_f64` and always builds,
  otherwise a rational-zero imaginary part demotes to the real -- a normalized
  exact zero is always an i31, so the demotion test is complete),
  `_cadd/_csub/_cmul/_cdiv` (part-wise through the exact `_rat_*` helpers, so
  funnels match real arithmetic, finishing through `_ccomplex`),
  `_cneg` (part-wise negation: float parts through `f64.neg`, so `-0.0`
  survives, unlike a `_csub`-from-zero fold).
- Call-site (`WasmComplexCompiler` + cases in
  `WasmExprCompiler.compileCons()`): `complex`, `complexp`, `realp`,
  `realpart`, `imagpart`, `conjugate`, `phase`, always-complex `sqrt`,
  always-complex-capable `cis`/`asinh`/`acosh`/`atanh` (real arguments run the
  `WasmInverseHypCompiler` cores inside the same runtime test; `acosh`/`atanh`
  build a temporary `(x, +0.0)` complex for the plane arm when the argument
  leaves their real domain, like the `sqrt` site's negative root),
  the real-domain escapes `log`/`asin`/`acos`/`expt` (below), and the
  complex-aware `+ - * / abs expt = /=` orderings, `min`/`max`, and the
  fifteen unary math functions. Literals emit inline in code position and
  under `quote` (the reader already canonicalized them, so parts plus tag plus
  `struct.new` is the value).
- Complex block (`WasmComplexBlock`, only in a program that may observe a
  complex): the same formulas emitted ONCE as functions, for a site whose complex
  arrives through a variable ("A complex through a variable" below).

## Steering: `containsComplex`, before everything else

Every steered site tests `LispMacroExpander.containsComplex` FIRST -- before
`hasDoubleLiteral`, before int-fusion, before the condition-i32 fast path
(`WasmComparisonCompiler.tryCompileConditionI32` bails on it). A literal
double beside a syntactic complex must never take the f64 path (it would land
in `_as_f64`'s complex arm instead of computing). `=`/`/=`/orderings compile
at any arity (the n-ary expansion binds operands to temporaries, which would
hide the complex from the gate): `=`/`/=` compare part-wise through
`_rat_cmp_bits` when a complex is present at run time (it handles every real
tier, floats included), orderings/`min`/`max` land in `_type_err_real`.
`zerop`/`1+`/`1-`/`plusp`/`minusp` ride the expansions. `eql` compares part-wise
through the shared `_equal` (its eql base case is part-wise eql for real
parts); `equal` recurses through `_equal` on the parts. `eq` needs nothing
(identity, like the interpreter). `numberp` gains the arm; `complexp` is one
`ref.test`; `realp` is every real tier minus complex.

`_as_f64` gains one arm, between the ratio rung and the non-number landing: a
complex lands in `_type_err_num` -- never a silent real-part extraction (that
would be a wrong number). It is the JVM `_dbl` corner's twin: catchable and
correctly rendered in EH mode. Complex PARTS -- always real -- flow through
the same function everywhere else, per `.kb/wasm-shared-coercion.md`.

`DoubleValuedForms.certainlyDouble` answers false for a float-contagious call
with a complex operand ANYWHERE: `(+ #c(1 2) 1.5)` answers a complex, and the
printer would otherwise cast it to `TYPE_FLOAT` and trap. It scanned the
operands in one pass until 2026-09-11 (`.todo/779`) and answered true at the
first literal double, so a complex to the RIGHT of one was never reached and
`(princ (+ 3d0 #c(1d0 2d0)))` trapped the module with a wasmtime "cast
failure" -- not the catchable landing the corner above describes. The complex
scan is a pass of its own now, ahead of the double scan. Int-fusion refuses a tree
carrying a syntactic complex outright (`WasmIntFusionCompiler.tryCompile`,
`tryCompileRaw` and the `compileRawStore` raw path all bail on
`containsComplex`, the JVM twin's identical gate): a complex literal or
`complex` call does classify as a guarded leaf, but the guarded-leaf bail
falls back to the real-only `_rat_*` helpers, which signal for a holder
instead of answering complex -- measured 2026-09-09 (.todo/755), where
`(print (+ #c(1 2) #c(3 4)))` beside a condition-rendering handler trapped
with `Expected integer, got: #C(1 2)`. The triggering shape is any `let`/`setq`
value holding the arithmetic tree -- user-written, or the print-hook's
implicit `__poN_v` let once condition reports route -- because the raw-store
fusion has no caller-side steering gate to hide behind. Refusing costs nothing
observable: a complex leaf can never take the integer fast path, so fusion
over one is a guaranteed bail plus a trap.

## A complex through a variable (2026-10-06)

The steering is syntactic, so a complex reaching an operator through a parameter, a
global, a list element or a designator's argument landed in the real-only funnels:
`_as_f64`'s complex arm or `_rat_num`'s landing, a trap outside EH mode and a wrong-type
report inside it (`=` and `abs` included, which the JVM's `_cmpb`/`_abs` arms already
answered). In a module whose program may observe a complex (`compiler/ComplexCapability`,
the JVM's gate scan) the generic entries find it at run time; every other module is
byte-identical at every level. As on the JVM, an arm sits where the real body would
REJECT a complex:

- `_rat_add`/`_rat_sub`/`_rat_mul`/`_rat_div` (`WasmRatioRuntimeBuilder.emitComplexArm`,
  to `_c_add`/`_c_sub`/`_c_mul`/`_c_div`): on the float branch, and once the two exact
  integers are ruled out (ahead of the ratio arm's `_rat_num` and the non-rational
  landing). The float branch first answers two floats straight out of their boxes
  (`emitFloatPairArm`, no `_as_f64` call), so the arm is a mixed pair's cost only; the
  i31 head and the exact-integer path are untouched.
- Unary `-` of a non-float tests for a complex and negates it through `_c_neg`
  (`WasmArithCompiler.compileUnaryNegate`) -- `_rat_sub(0, x)` would turn a `-0.0` part
  into `+0.0`.
- The complex block (`WasmComplexBlock`): one function each for `=`, `abs`, the eight
  float unary functions without a real-domain escape, and `expt`, appended right before
  the user functions (`complexBlockFuncBase()`, the `_lit_stage` precedent: no fixed index
  moves). A body is the formula a literal-steered site emits inline, emitted once over
  the parameters by the same `WasmComplexCompiler` emitter (`emitEqPair`, `emitAbsOf`,
  `emitUnaryMathOf`, `emitExptOf`). The block is built before any body compiles, and a
  site calling an entry records the fdlibm functions that body calls as its own, so they
  get real bodies exactly when a reachable site calls the entry.
- Sites keep their real paths inline and test in front of them: `abs` on its non-float
  arm; a unary function whose argument may hold a complex
  (`ComplexCapability.mayYieldComplex`, the JVM's predicate: a variable or a call, or an
  arithmetic operation over one), reading a float argument straight out of its box
  behind the test; `expt` on each path for exactly the operand that can be complex there
  (the base alone on the integer-exponent loop).
- `=` calls the block's `=` (`WasmComparisonCompiler.emitGenericCompare`, the fused
  compare's bail too) instead of `_rat_cmp_bits`, which every ordering shares: there a
  complex already lands in the REAL report an ordering must give, so the part-wise `=`
  cannot be that function's arm. The entry answers an i31 pair and a float pair before
  its complex test.
- The type-test fold (`.kb/wasm-ref-type-fold.md`) deletes every arm and test whose
  operand set holds no `TYPE_COMPLEX`, and the shake the entries no site still calls --
  most gated programs, where the one complex a `sqrt` can make never reaches the
  arithmetic, keep none of it.

An operator whose own form spells a float literal (`(+ z 1.5)`) is the next section's.

Measured 2026-10-06 (wasmtime 49, linux/amd64): the size-report and bench-report programs
are byte-identical at every level, P1 and component. Where no complex can flow (the
micro programs: a `sqrt` beside a `+`, `=`, `abs`, `sin`, `expt` over parameters) the fold
leaves the module within 10 B of its old size; where one can,
`examples/ml/linear-regression.lisp` grew 74,550 -> 80,203 B at the default level (the
block's `expt`, the `_c_*` twins). Speed where a complex CAN reach the measured function
(it is handed one once, statically, so the fold keeps every arm): the first shapes cost
float `+`/`*` +13%, `=` +7%, unary +8%, `expt` +10%; with the float pair answered ahead
of the `_rat_*` arm, the `=` entry's i31 and float pairs, and the unary site's inline
float read, float `+`/`*` is 15% FASTER than before, `=` 11%, unary 5%. `expt` stays
+10-15% in that micro program only because the old fold had proved its operand never a
float and deleted the float branch, which the reachable `_c_*` twins' float answers now
keep. An n-body whose float arithmetic a complex can reach runs 6% faster.

## A complex beside a float literal

A float literal routes `(* 2.0 z)`, `(abs (* 2.0 z))` and `(= (* 2.0 z) 1.0)` onto the f64
path, whose `_as_f64` lands a complex in the REAL report. In a module whose program may
observe a complex, a site whose operands may hold one (`ComplexCapability.mayYieldComplex`)
takes `WasmFloatOperands`; every other module, and every other site, is byte-identical.

- Every operand is evaluated into a temporary first -- left to right, as the interpreter
  evaluates an operation's arguments before applying it -- then `ref.test TYPE_COMPLEX`
  over the ones a variable or a call produced picks the `_rat_*` fold (whose arms answer
  the complex; `_c_neg` for a unary minus) or the f64 fold over the temporaries, where a
  number literal is its `f64.const` rather than a box `_as_f64` opens again. An inner
  float-literal operation is an operand like any call: it boxes its result, as it always
  did, and signals before the outer one's later operands run.
- Consumers: the arithmetic (`WasmArithCompiler`), the comparison on proven doubles
  (`emitGenericCompare`: the block's `=`, `_rat_cmp_bits` for an ordering) and `abs` (the
  block's `abs`). `min`/`max`, `atan`'s two-argument form, the rounding family and
  `random` need nothing: their `_as_f64` already reports the complex an operand computed
  as REAL under their own operator.
- The printer's shortcut (`DoubleValuedForms.certainlyDouble`) answers false in such a
  program for a form with an operand that may hold a complex: its `ref.cast` to
  `TYPE_FLOAT` would trap on the complex.

The order, the pairwise generic fold and the pins are the JVM twin's
(`.kb/jvm-complex.md`, "A complex beside a float literal").

## Errors: the interpreter's texts, the backend's classes

- Ordering/`min`/`max` over a complex: `FUNC_TYPE_ERR_REAL`
  (`<: The value #C(1 2) is not of type REAL`), catchable in EH mode as a `type-error`
  (`.kb/error-handling.md`, "A non-number reaching arithmetic").
- Non-real `complex` parts, `realpart`/`imagpart`/`conjugate`/`phase` of a
  non-number: `_type_err_num` (`The value ... is not of type NUMBER`), the same funnel
  as every other mistyped arithmetic operand.

## Transcendentals: fdlibm, the interpreter's formulas term for term

`sqrt` (except a non-negative real, which keeps native `f64.sqrt`), `abs`
(fdlibm `hypot`), `phase` (fdlibm `atan2`, whose rungs answer the imaginary
axis and the signed zeros: `copysign(pi/2, y)` for a nonzero `y` over either
zero, a zero `y`'s own sign over `+0` and `copysign(pi, y)` over `-0` -- the
axis the old hand-rolled assembly got wrong, `.todo/766`), `expt` (the exact
squaring loop for an i31 exponent over an EXACT base, `exp(w*log z)` for
anything with a float part, `Environment.exptComplex`'s rule; a zero base takes
`emitZeroBasePow` first, `.kb/error-handling.md` "A zero base on the complex `expt`
path") and the fifteen
unary functions run the interpreter's formulas over calls into the fdlibm
runtime (`WasmTranscendentalCompiler`, `.kb/transcendentals.md`), so since
2026-09-17 they answer the interpreter's BITS; `isCloseTo` pins that predate
it still pass, and `ci-spec.yaml`'s `transcendentals-bit-identical-cross-backend`
prints the digits.

## asin/acos: the branch cut is the contract, not the digits

The cut rule is one rule for all three implementations and lives in
`.kb/jvm-complex.md` ("The asin/acos branch cut"): Kahan's form over
`u = sqrt(1 - z)` and `v = sqrt(1 + z)`, whose `0.0 - im` / `0.0 + im` make an
imaginary zero of either sign land on ONE sheet, so the side of the cut is the
real part's and `(asin #c(2d0 0d0))` equals `(asin #c(2d0 -0d0))`.
`emitAsinAcosRootsInto` builds the pair; the real parts go through fdlibm's
`atan2` and the imaginary parts through `WasmInverseHypCompiler`'s asinh (the
interpreter's grouping over fdlibm `log1p`/`hypot`/`log`), so the magnitudes are
the interpreter's bits.

The ZEROS are exact here too, and have no tolerance: a real argument inside
`[-1, 1]` leaves both roots real, the asinh argument is a difference of zeros,
and the asinh core answers `0` at `0` -- `(asin (complex 0d0 0d0))` prints
`#C(0.0 0.0)` on every backend. What the E2E case
(`ci-spec.yaml`'s `complex-asin-acos-branch-cut`) compares is exactly that: the
two zero signs answering one value, the sign of the imaginary part on the cut,
and the exact zeros.

## Real arguments that leave the real domain (`.todo/763`, 2026-09-11)

`log` of a negative, `asin`/`acos` beyond `[-1, 1]` and `expt` of a negative
base to a non-integer power answer the plane rather than NaN, the same escape
`sqrt`/`acosh`/`atanh` already took. The rule and the reasoning are the JVM
twin's (`.kb/jvm-complex.md`, "Real arguments that leave the real domain") --
including `expt`'s `|x|^y` turned through `y*pi` radians rather than
`exp(w*log z)` over a complex built for the purpose. Two things are this
backend's own:

- **Where the arm lives.** `WasmComplexCompiler.compileLog` /
  `compileAsinAcos` shadow the real calls (fdlibm `log`, `asin`/`acos`) inside
  one runtime domain test, and `emitPlaneArmAt` builds the temporary
  `(x, +0.0)` complex the plane arm runs on -- `compileAcosh`'s shape exactly.
  `expt`'s escape is `WasmExptCompiler.emitFloatPath` plus
  `WasmComplexCompiler.emitNegativeBasePowInto`, over fdlibm `pow`/`cos`/`sin`
  (`WasmLispCompilerIntegrationTest#compileAndRunRealDomainEscapesAnswerThePlane`).
- **The arm is emitted per CALL, not always.** The helpers here are
  unconditional, but these arms are INLINE at the site, so the site reads the
  same `LispMacroExpander.escapesToComplex` predicate the JVM's gate does: a
  non-negative literal argument to `log`, a literal inside `[-1, 1]` to
  `asin`/`acos`, a literal integer exponent or non-negative literal base to
  `expt` all keep the small real-only shape. `(expt n 2)` and `(expt 10.0 n)`
  cost nothing.

## The scalar backend refuses

`--no-gc` has no complex representation (`.todo/037-number-extensions.md`):
a `complex`/`complexp`/`realp`/`realpart`/`imagpart`/`conjugate`/`phase` call
or a `#C` literal is a compile-time `UnsupportedOperationException` naming the
form and the function -- never a trap, never a wrong number
(`NoGcWasmCompilerTest.rejectsComplexNumbers`). The real-domain escapes need
no carve-out there: `log`, `asin`, `acos` and `expt` are not in the scalar
backend's operator set at all, so a program using one is already refused at
compile time. Its `sqrt` is a bare `f64.sqrt`, so a negative argument keeps the
NaN -- the one documented place where that answer survives.

## The two-argument `atan` and `log` (`.todo/762`, 2026-09-11)

- `(atan y x)` is `WasmComplexCompiler.compileAtan2`, which runs `phase`'s OWN
  `emitAtan2Into` -- fdlibm's `atan2`, so the axes, the signed zeros and the
  off-axis values are all the interpreter's. Both arguments must be real; a
  SYNTACTIC complex lands in `_type_err_real` through `emitRealOperandGuard`
  (renamed from `emitMinMaxComplexGuard`, which min/max still shares), the same
  syntactic-steering corner min/max has always had.
- `(log n base)` is `compileLogBase`: the quotient of two logarithms, with the
  complex-capable spelling running both through `compileLogOf` and dividing
  with `_c_div`, and the real one through `WasmTranscendentalCompiler.compileArg`
  into a plain `f64.div` -- so `(log 8 2)` pulls in no complex runtime at all. Whether
  a site is complex-capable is `LispMacroExpander.escapesToComplex` over BOTH
  arguments, the predicate the JVM's gate reads.
- `_c_div` gained the JVM's arm: neither operand a `TYPE_COMPLEX` -> delegate to
  `_rat_div`. It answered a real here before (its `_c_complex` tail canonicalizes
  an exact zero imaginary part away, where the JVM's float path could not), but
  through the `c^2+d^2` denominator -- so the two backends now agree bit for bit
  on `(log n b)` when both logarithms stayed real.
- The quotient is fdlibm `log` over fdlibm `log` on every backend, the same bits
  everywhere (`(log 8 2)` is `3.0`); `ci-spec.yaml`'s `atan2-and-log-base` pins
  the contract (the exact axes, the identity against `phase`, the magnitude and
  the TYPE) and `transcendentals-bit-identical-cross-backend` the digits.

## `_c_div`'s float arm is Smith's form (`.todo/779`, 2026-09-11)

The form, why it is not the `c^2+d^2` denominator, and the SBCL measurement are
`.kb/jvm-complex.md`'s "Complex division is Smith's form" -- one form, three
implementations, changed together. What is this backend's alone:

- The whole body used to be ONE path over the `_rat_*` helpers, which absorb float
  contagion for free. Smith's fold cannot ride them (`r = d/c` would make an exact
  ratio and the comparison `|c| >= |d|` has no `_rat_` spelling that is cheaper
  than the fold), so the body now BRANCHES: any of the four parts a `TYPE_FLOAT`
  coerces all four through the one shared `_as_f64` and computes the fold in raw
  `f64` instructions (`f64.abs`, `f64.ge`, `f64.div`), boxing the two parts back
  through `_c_complex`. Locals 9-14 are that arm's raw parts, `r` and `den`.
- The EXACT path below it is byte-for-byte what it was, denominator included: it
  now only ever sees exact parts, where nothing rounds or overflows, and a zero
  divisor still fails inside `_rat_div` the way a real `(/ x 0)` does.
- Raw `f64` instructions round exactly as the JVM's `DDIV`/`DMUL` do, so the two
  backends agree BIT for bit on a float complex quotient (as they do on the
  transcendentals since 2026-09-17).
  `WasmLispCompilerIntegrationTest#compileAndRunComplexFloatDivisionIsSmithsForm`
  therefore pins digits, not tolerances.

## Known corners (documented, matching the JVM where stated)

- A runtime-real value under a steered operator demotes exactly, but a float
  real answers a float-zero-imagined complex -- the JVM `_ccomplex` float path
  does the same (`_cneg` included: it always ends in `_ccomplex`).
- Exact parts are exact at any magnitude, like real ratio arithmetic (the
  ratio components became exact integers 2026-10-03, `.kb/wasm-bignum.md`,
  "Ratios"; they wrapped past i32 before).
- The emitted `eval` and the runtime reader have no `#C` arm (the JVM twin
  neither); `mod`/`rem`/`gcd`/`lcm`/`isqrt`/`signum` and the rounding family
  treat a complex like any other mistyped operand. `signum` is 754's audit.

Pinning tests: `WasmLispCompilerIntegrationTest#compileAndRunComplex*`
(mirrors `LispEvaluatorTest`'s `evalComplex*` case for case, plus a
`--component` smoke leg); `NoGcWasmCompilerTest#rejectsComplexNumbers`;
`WasmLispCompilerIntegrationTest#complexThroughAVariable` and
`#complexBesideAFloatLiteral` (every optimize level, P1 and component, outside
and inside EH mode) with their `ComplexThroughAVariableFixture` /
`ComplexBesideAFloatLiteralFixture` twins and `ci-spec.yaml`'s
`complex-arithmetic-through-a-variable` / `complex-beside-a-float-literal`.
