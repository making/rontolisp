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

## Errors: the interpreter's texts, the backend's classes

- Ordering/`min`/`max` over a complex: `FUNC_TYPE_ERR_REAL`
  (`<: The value #C(1 2) is not of type REAL`), catchable in EH mode -- as a `simple-error`, the documented
  instance-less-throw divergence (the interpreter and the JVM answer
  `type-error`; `.kb/error-handling.md`).
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
anything with a float part, `Environment.exptComplex`'s rule) and the fifteen
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

- A complex arriving only through a variable beside a real operator takes that
  operator's ordinary path (the steering is syntactic, like the JVM's
  `hasComplexOperand`).
- A runtime-real value under a steered operator demotes exactly, but a float
  real answers a float-zero-imagined complex -- the JVM `_ccomplex` float path
  does the same (`_cneg` included: it always ends in `_ccomplex`).
- Exact parts past the i32 ratio-component range wrap, exactly like real
  ratio arithmetic (`.kb/wasm-bignum.md`, "Deliberate limits").
- The emitted `eval` and the runtime reader have no `#C` arm (the JVM twin
  neither); `mod`/`rem`/`gcd`/`lcm`/`isqrt`/`signum` and the rounding family
  treat a complex like any other mistyped operand. `signum` is 754's audit.

Pinning tests: `WasmLispCompilerIntegrationTest#compileAndRunComplex*`
(mirrors `LispEvaluatorTest`'s `evalComplex*` case for case, plus a
`--component` smoke leg); `NoGcWasmCompilerTest#rejectsComplexNumbers`.
