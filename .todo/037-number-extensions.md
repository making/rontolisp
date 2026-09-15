> **Update 2026-09-15 (--no-gc exact i64-vs-f64 comparison landed):** a mixed
> integer/float `=`/`<`/`>`/`<=`/`>=` now compares exact values on the scalar
> backend too -- the float's exact binary value against the i64 -- instead of
> coercing the integer through f64, so `(= 9007199254740993 9007199254740992.0)`
> is NIL and `(> 9007199254740993 9007199254740992.0)` is T. Only a pair whose
> static types are exactly INT-ish and FLOAT takes the exact path (a VOID side
> keeps the old join materialization); the emission decomposes the float from its
> raw bits and shifts the mantissa up with a survival check, or divides down with
> a truncating quotient plus remainder (NaN unordered, infinities beyond every
> i64), inlined per site with no shared helper, one local per site plus a
> per-function scratch triple (ten thousand mixed sites in one function still
> compile -- the first cut grew three scratch locals per site and tripped
> wasmtime's locals cap on the differential sweep). `min`/`max` decide mixed
> rounds through the same helper but still answer the joined f64 values (an
> integer winner prints as its correctly-rounded float -- values provably
> identical to the old fold, rounding only collapses, never reverses). No
> Lisp-level lowering changed, so `inferTypes` never sees it. Measured:
> **numbers 220 -> 220, misc 38 -> 38, 0 regressed** (interpreter untouched;
> this path is not ANSI-measured) plus a 10,716-case no-GC-vs-interpreter
> differential sweep with 0 mismatches. Pin:
> `WasmLispCompilerIntegrationTest#noGcIntFloatComparisonIsExactPast2Pow53`
> (past 2^53 both signs, minI64, subnormal, 1e300, fractional, Inf/NaN,
> let-carried, branch consumption, min/max parity); `wasm-nogc.md` EN/JA gained
> the contract sentence. Design in `.kb/no-gc-scalar-wasm.md`. Remaining work:
> WASM-GC exact float-vs-exact below.
>
> **Update 2026-09-15 (float-vs-exact comparison landed on interpreter+JVM):** a float
> beside an exact number now compares EXACT values -- the float's exact binary value
> (as `rational` answers it) against the exact operand -- instead of float contagion,
> so `(= 1.0 (+ 1 tiny-ratio))` is NIL even though the ratio floats back to `1.0`.
> Interpreter `Environment.compareNumeric` grew a `compareFloat` arm
> (`rationalOfDouble` + cross-multiplication; NaN unordered, infinities beyond every
> exact number on their side, the non-number funnel stays `Expected number` per
> `.kb/error-handling.md`); the JVM mirrors it in bytecode (`_cmpb`/`_cmp` mixed arms
> decompose the double through `_frat` and cross-multiply against (`_ratNum`,
> `_ratDen`), no new runtime class, maxStack 5 on `_cmpb`) and `JvmComparisonCompiler`
> takes the unboxed DCMPL only when BOTH operands are `isDefinitelyDouble` (the
> min/max gate's distinction) -- a double literal beside a computed exact operand now
> goes through `_cmpb`. Measured as a diff of failing ANSI test NAMES: **numbers 237
> -> 220 (16 fixed), misc 38 -> 38, 0 regressed**. Fixed: `<.17`/`=.17`/`/=.17`/
> `>=.17`, `=.18`/`/=.18`/`<=.18`/`>.18` (eight, not six: `/=` rides on `=`; the
> one-sided mirrors never failed) plus `BIGNUM.FLOAT.COMPARE.1A-4B` (deterministic now,
> not draw luck: the strict gap decides exactly whatever `random` draws). `SQRT.17`
> flipped the other way by draw luck and is excluded from the claim (its operands stay
> under 2^53, where both semantics agree -- verified, not assumed). No-GC has since
> landed its exact i64-vs-f64 path (top banner -- the `#noGcFloatExactComparison`
> comment's "remaining work" now points at the landed pin); WASM-GC keeps f64
> comparison with representable-range agreement pinned
> (`WasmLispCompilerIntegrationTest#floatExactComparison`,
> ci-spec `float-exact-comparison`, `=` doc EN/JA), but a near tie still rounds to
> equality there -- `(= 0.6666666666666666 2/3)` is T on WASM-GC, NIL on
> interpreter/JVM (probed; even tiny ratios sit strictly inside half an ulp, so no
> component bound saves f64 -- the first version of the `.kb/wasm-bignum.md` note
> claimed one and was wrong the same day). The exact path for WASM-GC (big tier +
> call-site gate, design in `.kb/wasm-bignum.md`) is remaining work below.
> `.kb/jvm-double-arithmetic.md` gained the gate rule.
>
> **Update 2026-09-15 (ratio->float conversion landed):** `LispRatio.doubleValue`
> is now the correctly-rounded nearest double (exact `BigInteger` quotient: a
> 56-bit head plus the remainder as the sticky bit, round-half-even, denormalizing
> to signed zero) instead of the DECIMAL64 16-digit rounding; the JVM `_dbl` ratio
> arm calls the same contract as a new generated `_ratToDouble` method (no new
> runtime class, so no travelling-list change -- the class stays self-contained),
> and WASM-GC needed no code change (its `_as_f64` ratio arm is an f64 division,
> correctly rounded for the i32 components it holds) -- only a pinning test.
> Measured as a diff of failing ANSI test NAMES: **numbers 240 -> 234 (6 fixed),
> misc 38 -> 38, 0 regressed**. Fixed: `RATIONAL.1/.3`, `RATIONALIZE.1/.3`,
> `/.12` (the round trips are identities now: `rational` is exact and the exact
> quotient converts back to its own double) plus `*.12` as a knock-on (its
> `(expt 2 -i)` ratios float through the same conversion). `RATIONAL.3`/
> `RATIONALIZE.3` pass deterministically, not by draw luck, for the same reason.
> Three traps: (1) a tie probe written as `1.0000000000000002` fails -- the
> midpoint `(2^53+3)/2^53` rounds to the EVEN neighbor `1+2^-51`
> (`1.0000000000000004`), and the literal was the bug; (2) tie vectors need
> 54-bit midpoints, so below `2^53` every iteration asserts but above it most
> midpoints are integers and silently skip -- the test asserts the count (300);
> (3) `_ratToDouble` costs ~300 bytes in every numeric program, which tripped the
> 8,000-byte budget in
> `JvmLispCompilerTest#aProgramThatNeverNamesAnArrayOperatorCarriesNoArrayRuntime`
> (8,295 actual) -- raised to 8,400 with the reason on the assertion, not gated:
> a may-produce-a-ratio gate needs the complex gate's recompile net to stay sound
> (a missed source is a `NoSuchMethodError`) and is disproportionate for ~300
> bytes. `*.12` leaves the watch-list below.
>
> **Update 2026-09-15 (Slice C landed):** `logcount`, `rationalize` and
> `integer-decode-float` ship as prelude defuns (`LispPreludeLibrary`, the
> `decode-float` precedent -- one Lisp implementation runs on the interpreter,
> the JVM and WASM-GC, first-class and multi-value free, no new compiler
> classes) + `LispNames` + `CL_FUNCTIONS` (three names move out of
> `CL_EXPORTED_ONLY`, the 978 externals unchanged) + `ci-spec.yaml` cases +
> EN/JA docs. Measured as a diff of failing ANSI test NAMES: **numbers 259 ->
> 243 (16 fixed), misc 52 -> 38 (15 fixed + 1 surfaced), 0 regressed** (31
> total). Fully fixed: `LOGCOUNT.1-.8` + `ERROR.1/.2`, `RATIONALIZE.2` +
> `ERROR.1/.2/.3`, `MAX.2`/`MIN.2` (knock-on: numbers-aux's
> `+rational-most-*+` constants bind through `rational-safely` now that
> integer-decode-float exists); misc 4 logcount + 11 rationalize. Surfaced
> second reasons, zero true regressions: `LOGCOUNT.ERROR.3`/
> `RATIONALIZE.ERROR.4` (universe cascade, `.todo/715` §1 out of scope),
> `RATIONAL.1`/`RATIONALIZE.1`/`RATIONALIZE.3`/`/.12` (the ratio->float
> conversion defect -- every collected rational probed EXACT, e.g.
> `(float (/ 1 8388608))` answers `1.192092895507812e-7`, not `2^-23`; the
> mediant's round-trip guard + exact fallback pass under a correct conversion
> by construction), `MISC.358` (now reaches `LDB-TEST`, out of scope).
> Random churn unchanged: eight `BIGNUM.FLOAT.COMPARE.*`, `SQRT.17`,
> `RATIONAL.3`.
>
> Backend notes: no-GC lowers `logcount` through a new `expandLogcount` scalar
> loop and refuses `integer-decode-float`/`rationalize` outright (secondary
> values / ratio answers have no scalar representation -- the `RATIONAL`
> precedent). WASM-GC pins integers (`rationalize` answers an integer-valued
> float through a fast path with no fraction involved), `0.0` and ratios; a
> fractional float traps fail-stop on big-denominator intermediates, exactly
> as `(/ 1 (ash 1 52))` does (`.kb/wasm-bignum.md`, ratio components stay
> i32). Three traps, all recorded where the next reader looks
> (`.kb/adding-primitives.md` gained the prelude checklist,
> `.kb/no-gc-scalar-wasm.md` the determinism rule): (1) a `--no-gc`-shared
> lowering must use FIXED temporary names -- an `MV_COUNTER` gensym re-expands
> to a fresh local on every `inferTypes` fixpoint pass and the compile hangs
> forever (found as a spinning surefire fork, diagnosed with `jstack`);
> (2) WASM-GC unit tests reaching a prelude defun must use
> `compileAndRunPrelude` (the CLI pipeline's splice); (3) `(logcount -8)` is
> 3, not 1 (`-8` is `...11111000`, three zero bits -- caught before landing).
>
> **Update 2026-09-15 (Slice B `rational` landed):** exact rational on all four
> backends -- interpreter `Environment` bit decomposition + JVM `_rational`
> (`_frat` + `_rat`, new `JvmRationalCompiler`) + WASM-GC `WasmRationalCompiler`
> (bit decomposition through the existing `_int_new`/`_big_ash` + `_rat_div`,
> so the answer is exactly what `(/ num den)` of the same integers would be)
> + no-GC compile-time refusal (no ratio representation; never a trap, never a
> float masquerading as exact) + `BuiltinFunctionWrappers` first-class entry +
> `CL_FUNCTIONS` classification (`RATIONAL` was already external via `CL_TYPES`,
> so the 978-name externals set is unchanged) + `ci-spec.yaml` case + EN/JA
> docs. Measured as a diff of failing ANSI test NAMES: **numbers 274 -> 259
> (15 fully fixed), misc 59 -> 52 (7 fully fixed), 0 regressed** (22 total).
> Fully fixed: `*.7`, `*.10`, `<=.17`, `>.17`, `<.18`, `>=.18`,
> `BIGNUM.FLOAT.COMPARE.5A/.5B/.6A/.6B`, `RATIONAL.2`, `RATIONAL.ERROR.1/.2/.3`,
> `SQRT.9`, `MISC.228A/.283/.362/.367/.376/.380/.381`. Three second reasons
> surfaced (all still failing under a new line, zero true regressions):
> (1) the other eight `*.17/*.18` + eight `BIGNUM.*` now FAIL on float-vs-ratio
> `=`/`compare` semantics -- `(= 1.0 (+ 1 tiny-ratio))` answers T here where
> SBCL answers NIL; the `rational` values themselves are exact (probed:
> `(rational 3.4e38)` is `340282346638528859811704183484516925440`, exact).
> (2) `RATIONAL.3` fails on the `ratio -> float` round trip
> (`LispRatio.doubleValue` goes through DECIMAL64, 16 digits) -- pre-existing,
> adjacent to `rational` but not in it. (3) `RATIONAL.ERROR.4` now reaches the
> universe cascade (`*MINI-UNIVERSE*` unbound, `.todo/715` §1 out of scope);
> `MISC.382` moved to `LDB-TEST` undefined the same way. `SQRT.17` FAILs in
> both runs with different draws (the known `random` flake, failing on
> baseline too). WASM-GC pins only the exactly representable range: a ratio
> whose reduced components leave i31 wraps there, exactly as `(/ a b)` of the
> same integers does -- no new failure mode.
>
> **Update 2026-09-15 (ash huge-count defect closed):** the `(int) count`
> narrowing wrapped an out-of-int-range negative count positive and built a
> monster bignum. Fixed wide-first on the interpreter (`Environment`), the JVM
> (`_ash` + fused `_fxAsh`) and `--no-gc` (`compileAsh` clamps the right-shift
> magnitude at 63); WASM-GC already clamped (`_big_ash`/`_fx_ash`, pinned by
> test). Only magnitudes past the int range saturate (right) or signal (left,
> non-zero); int-range counts keep the width-aware paths. Measured as a diff of
> failing ANSI test NAMES: **numbers ASH.5 fixed, misc MISC.47/.48 fixed, 0
> regressed** (3 total). Two traps: (1) new tests must compare with `=` -- a
> regressed build prints the monster (quadratic hang); wasmtime `--invoke`
> parses ints as i32, so the no-GC test embeds the count in-program. (2) The
> first cut saturated at 64 for every value and regressed ASH.3 -- a bignum
> shifted right by 70 still has high bits; saturation past 64 is fixnum-only.
> Still open from this item's reach: Slice B/C (`rational`, `rationalize`,
> `logcount`, `integer-decode-float`), `MISC.512`, the six
> `LOGEQV/LOGNAND/LOGNOR.ERROR.1/.2` universe failures, the `*.12/*.17/*.18`
> remainders, no-GC `deposit-field`, and a limb-tier bignum COUNT on WASM-GC
> (`_int_val` traps -- fail-stop, no wrong answer).
>
> **Update 2026-09-14 (numbers Slice A landed):** `float-radix`, `logeqv`,
> `lognor`, `lognand` and `deposit-field` shipped on all four backends
> (interpreter `Environment` function + `LispMacroExpander` lowering +
> JVM/WASM-GC compiler cases + no-GC `expandMacro` for the four whose expansions
> use only scalar primitives + `BuiltinFunctionWrappers` first-class entries +
> `CL_FUNCTIONS` classification + `ci-spec.yaml` cases + EN/JA docs). Measured as
> a diff of failing ANSI test NAMES: **numbers +106 / misc +35, 0 regressed**
> (141 total). Two findings: (1) `deposit-field` is NOT `dpb` -- `dpb` deposits
> newbyte's LOW size bits (`dpb.lsp` checks `(logbitp (- i pos) newbyte)`) while
> `deposit-field` deposits newbyte's bits AT the field (`deposit-field.lsp`
> checks `(logbitp i newbyte)`); the first cut shipped the `dpb` spelling and
> `DEPOSIT-FIELD.1/.2` caught it. (2) `MISC.47/.48` are blocked by a
> PRE-EXISTING `ash` defect, not by `lognor` -- closed 2026-09-15, see the
> banner above; `lognor` faithfully computes `~a`.
>
> **Update 2026-07-05 (parse-number e2e):** `/=` shipped (pairwise-different
> expansion over `=`, variadic), plus lite `complex` (zero imaginary part
> only), float-type + computed-type `coerce`, and the predefined
> `*read-default-float-format*` (informational; every float is double).

# Number system extensions (`rational`, `rationalize`, `complex` numbers, `realp`, `complexp`, `realpart`, `imagpart`, `phase`, `conjugate`, `integer-decode-float`, `scale-float`, `float-radix`, `decode-universal-time`, `encode-universal-time`)

**Status:** partially implemented. Shipped: `/=`, lite `complex`, float-type
`coerce`, `*read-default-float-format*` (2026-07-05); full complex tower
(.todo/751-754); `float-radix`, `logeqv`, `lognor`, `lognand`,
`deposit-field` (Slice A, 2026-09-14); the `ash` huge-count fix (2026-09-15);
`rational` (Slice B, 2026-09-15); `logcount`, `rationalize`,
`integer-decode-float` (Slice C, 2026-09-15); exact ratio->float conversion
(2026-09-15); float-vs-exact `=`/comparison on the interpreter and the JVM
(2026-09-15) and exact i64-vs-f64 `=`/comparison/`min`/`max` on `--no-gc`
(2026-09-15 -- WASM-GC exact is open, see the top banner).
Open: the watch-list in the top banner. Full complex numbers and
time decomposition are niche (low priority).

## What's missing

RontoLisp has the core numeric tower: integers (with `BigInteger` bignum), floats, and rationals (`LispRatio` via `BigInteger` numerator/denominator). Arithmetic (`+`, `-`, `*`, `/`), comparison (`=`, `<`, `>`, `<=`, `>=`), rounding (`truncate`, `floor`, `ceiling`, `round`), modular (`mod`, `rem`), absolute (`abs`), root (`sqrt`, `isqrt`), power (`expt`, `exp`, `log`), trigonometric (`sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `sinh`, `cosh`, `tanh`), number theory (`gcd`, `lcm`), sign (`signum`), and predicates (`numberp`, `integerp`, `floatp`, `rationalp`, `zerop`, `plusp`, `minusp`, `evenp`, `oddp`).

### Missing numeric functions

Shipped since this table was written: `float-radix` (Slice A), `complexp`,
`realpart`/`imagpart`, `conjugate`, `phase` (.todo/751-754), exact ratio->float
conversion (2026-09-15: `RATIONAL.1/.3`, `RATIONALIZE.1/.3`, `/.12`, `*.12`),
exact float-vs-exact comparison on the interpreter and the JVM (2026-09-15: the
eight `*.17`/`*.18` plus `BIGNUM.FLOAT.COMPARE.1A-4B).

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `rational` | Exact rational from float: `(rational 1.5)` -> `3/2` | Easy |
| `rationalize` | Simplest rational within tolerance: `(rationalize 1.4999999 0.01)` -> `3/2` | Medium |
| `realp` | True for real numbers (always t without complex) | Trivial |
| `integer-decode-float` | Decode float into significand, base, exponent | Easy |
| `scale-float` | Scale float by power of radix | Easy |
| `float-digits` | Significand digits of a float | Trivial |
| `float-sign` | Sign of a float, as a float | Trivial |
| `most-positive-double-float` | Largest representable double | Trivial |
| `most-negative-double-float` | Most negative representable double | Trivial |

### Missing time decomposition

`get-universal-time` already exists (see `.kb/time-environment-builtins.md`);
only the decomposition/composition pair is missing.

| Function | Purpose | Difficulty |
|----------|---------|------------|
| `decode-universal-time` | Break down universal time | Medium |
| `encode-universal-time` | Build universal time | Medium |

### Complex numbers

> **Update 2026-09-09 (.todo/754 landed):** the four-step split is complete --
> type system, corpus and docs are in (see the step list below). Remaining
> lite edges, all documented where they occur: `(integer 0 10)` upgrades to
> `integer` where SBCL answers `(mod 11)`; variable-carried complex arithmetic
> on the compiled backends steers syntactically (see `.todo/755` for the one
> place that steering goes wrong today).
>
> **Update 2026-09-11 (.todo/763 landed):** the NaN edge is closed on all four
> backends -- `log` of a negative, `asin`/`acos` beyond `[-1, 1]` and `expt` of
> a negative base to a non-integer power answer the plane, through the existing
> complex arms. The gate that keeps this free for ordinary numeric programs is
> per-CALL rather than per-mention (`.kb/jvm-complex.md`, "Real arguments that
> leave the real domain"). What does NOT cross is the syntactic steering above:
> `(+ 1.0 (log x))` still lands in the `Expected number` funnel rather than
> computing, which is the `.todo/755` shape with one more way in.
>
> **Update 2026-09-10:** a conformance sweep of the whole complex surface
> against SBCL 2.6.5 filed the rest of the gap as six items --
> `[[761-cis-asinh-acosh-atanh-are-not-defined]]`,
> `[[762-atan-and-log-take-only-one-argument]]`, `[[763-...-answer-nan]]`
> (now closed), `[[764-complex-asin-and-acos-pick-the-wrong-branch-on-the-cut]]`,
> `[[765-jvm-complex-acos-tan-and-tanh-answer-wrong-values]]` and
> `[[766-wasm-phase-is-wrong-when-the-real-part-is-a-zero]]` (both backend
> defects turned up by the sweep).

CL has a full complex number tower. RontoLisp implements it in four steps:
- 751 (done): `LispComplex` (real + imaginary parts) + reader/printer +
  interpreter arithmetic, predicates and accessors.
- 752 (done): JVM backend -- the `RontoComplex` holder, the gated `_c*` helper
  group (`.kb/jvm-complex.md`), every 751 interpreter case answering
  identically via `java Prog`.
- 753 (done): WASM GC -- the tagged `TYPE_COMPLEX` struct, the `_c*` runtime
  group plus call-site compilers (`.kb/wasm-complex.md`), every 751
  interpreter case answering identically via `wasmtime run test.wasm` (plus a
  `--component` smoke leg); the `--no-gc` scalar backend refuses complex
  construction and operators at compile time.
- 754 (done 2026-09-09): type system, corpus, docs. `typep`/`typecase`/
  `etypecase`/`check-type` arms for `complex` (incl. `(complex part-type)`)
  and the `real`-vs-`number` split (`(typep #c(1 2) 'real)` is NIL now);
  `subtypep` lattice (`complex` under `number`, `(complex x)` part-wise);
  `coerce` to/from `complex`/`real` (incl. `(complex part-type)` and computed
  designators); `upgraded-complex-part-type` (expansion-only, so all four
  backends share it);   `type-of` answering atomic `COMPLEX` (the numeric
  convention here is atomic, unlike SBCL's bounded specifier) with
  `class-of`/`find-class` following via the new built-in class.
  `signum` of a complex answers the
  unit vector on all four backends (JVM `_csignum`, WASM `_csignum`);
  `float`/`floor`-family/`numerator`/`denominator` over a complex signal the
  catchable "Expected real number" on all four (the funnel arms are one
  message now); `isqrt`/`mod`/`rem`/`gcd`/`lcm`/bitwise stay
  "Expected integer". `ci-spec.yaml` pins the contract (three cases; `+ - *`
  stay out -- `.todo/755`); per-operator EN+JA pages for the eight names.

### Implementation approach (remaining)

1. `rational` — convert float to exact ratio (Easy, useful; Slice B, DONE 2026-09-15).
2. `rationalize` — continued fractions (Medium; Slice C, DONE 2026-09-15).
3. `logcount`, `integer-decode-float` (multiple values, `.todo/032`; Slice C, DONE 2026-09-15).
4. `realp`, float constants (`float-digits`, `float-sign`,
   `most-positive/negative-double-float`), `scale-float` — smalls.
5. Time decomposition — useful but needs timezone handling; niche.

### Related

- `[[032-multiple-value-system]]` (`integer-decode-float`, `decode-universal-time` return multiple values)
- `[[035-type-system]]` (`coerce` between number types)
