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
`deposit-field` (Slice A, 2026-09-14); the `ash` huge-count fix (2026-09-15).
Open: Slice B/C plus the watch-list in the top banner. Full complex numbers and
time decomposition are niche (low priority).

## What's missing

RontoLisp has the core numeric tower: integers (with `BigInteger` bignum), floats, and rationals (`LispRatio` via `BigInteger` numerator/denominator). Arithmetic (`+`, `-`, `*`, `/`), comparison (`=`, `<`, `>`, `<=`, `>=`), rounding (`truncate`, `floor`, `ceiling`, `round`), modular (`mod`, `rem`), absolute (`abs`), root (`sqrt`, `isqrt`), power (`expt`, `exp`, `log`), trigonometric (`sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `sinh`, `cosh`, `tanh`), number theory (`gcd`, `lcm`), sign (`signum`), and predicates (`numberp`, `integerp`, `floatp`, `rationalp`, `zerop`, `plusp`, `minusp`, `evenp`, `oddp`).

### Missing numeric functions

Shipped since this table was written: `float-radix` (Slice A), `complexp`,
`realpart`/`imagpart`, `conjugate`, `phase` (.todo/751-754).

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

1. `rational` — convert float to exact ratio (Easy, useful; Slice B).
2. `rationalize` — continued fractions (Medium; Slice C).
3. `logcount`, `integer-decode-float` (multiple values, `.todo/032`; Slice C).
4. `realp`, float constants (`float-digits`, `float-sign`,
   `most-positive/negative-double-float`), `scale-float` — smalls.
5. Time decomposition — useful but needs timezone handling; niche.

### Related

- `[[032-multiple-value-system]]` (`integer-decode-float`, `decode-universal-time` return multiple values)
- `[[035-type-system]]` (`coerce` between number types)
