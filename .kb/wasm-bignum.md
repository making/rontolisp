# WASM exact integers: the three-tier representation

wasm-GC (Preview 1 AND `--component`; `--no-gc` is i64-native, unaffected) holds an exact
integer in exactly one of three tiers, and **always in the NARROWEST tier that holds it** --
so `ref.eq` stays a valid fixnum equality fast path and equal integers are always the same tier.

- **i31 fixnum** -- `[-2^30, 2^30-1]`.
- **`TYPE_BIGNUM`** -- `struct {i64}`, own rec group, the only `{i64}` struct in the module so
  `ref.test` discriminates it.
- **`TYPE_BIGINT`** -- `struct {(ref null $limbs)}` over **`TYPE_LIMBS`** (`array (mut i32)`),
  two's-complement little-endian 32-bit limbs, exact at any magnitude. One rec group with
  TYPE_LIMBS; the intra-group typed field reference keeps the pair structurally unique under
  wasm-GC canonicalization.

Equality per tier -- `ref.eq`, i64 field, `_big_eq` -- wired into eq/eql/`_equal`/`_hash`.

## Construction
- `_int_new (i64) -> eqref` (`WasmBignumRuntimeBuilder`) produces/demotes the i64 tier.
  `_int_val (eqref) -> i64` widens the two narrow tiers and TRAPS (explicit `unreachable`) on
  TYPE_BIGINT -- what keeps every boundary exact-or-trap. A NON-number lands in the catchable
  `_type_err_int` ([[error-handling]]).
- `_limb_new` (`WasmBigIntRuntimeBuilder`) canonicalizes and hands anything that fits to
  `_int_new`, so a TYPE_BIGINT has >= 3 limbs.
- Literals split the same way (`WasmEmitHelper.compileIntegerLiteral`, `compileBigIntegerLiteral`
  via `array.new_fixed`, **capped at 10000 limbs**), as does the emitted reader (`_rd_radix`,
  `_read_expr`'s decimal classifier, `_big_grow`).

## Runtime (`WasmBigIntRuntimeBuilder`), all `(ref null eq)` in/out
- **`_limb_*`** raw arrays: `_limb_of`/`_copy`/`_new`/`_get`/`_addsub`/`_neg`/`_mul`/`_cmp`/
  `_shl`/`_shr`/`_divrem_mag`/`_divmod_small`. `_limb_of` on a TYPE_BIGINT answers its OWN array
  -- read-only, `_limb_copy` before mutating. **Limbs are 32-bit because a limb product must fit
  an i64 -- core wasm has no widening 64-bit multiply.**
- **`_limb_divrem_mag`** is Knuth's Algorithm D (Hacker's Delight `divmnu64`, 32-bit digits in i64
  arithmetic): normalize v's top limb into fresh copies, estimate a quotient limb from two remainder
  limbs, correct it against v's second limb (zero for a one-limb v, whose estimate is exact), multiply-
  subtract, add back when still one too large. See "Limb division" below.
- **`_big_*`** dispatch all three tiers with an i64 fast path FIRST: `_add`/`_sub`
  (overflow-checked, promote not wrap), `_mul`, `_neg`, `_divrem` (truncating; a zero divisor
  signals through `_div_zero` in EH mode, [[error-handling]]), `_mod`, `_fdiv`
  (truncate/floor/ceiling/round-ties-even), `_cmp`, `_and/_or/_xor/_not`,
  `_ash` (**left shift past 2^25 bits traps as an allocation guard**), `_intlen`, `_logbitp`,
  `_gcd`, `_grow`, `_to_f64` (correctly rounded, "Ratios" below), `_print`/`_print_mag`/`_pad9`,
  `_eq`, `_hash`.

## Tier-aware dispatch sites
`WasmRatioRuntimeBuilder` (`_rat_add/_sub/_mul/_rem/_mod/_cmp/_div`, `emitIsExactInt`,
`emitLocalToF64`), `WasmBitwiseCompiler`, `WasmGcdCompiler`, `WasmLcmCompiler`, `expt` (loops
`_rat_mul`), `_print_val`/`_princ_val` (`emitPrintBignum`), `integerp`/`numberp`/`rationalp`, and
the emitted `eval` (`WasmEvalRuntimeBuilder`, JVM twin `JvmEvalRuntimeBuilder`).

`WasmIntConvCompiler` (`truncate/floor/ceiling/round`) is the intricate one: exact-int identity
first; a literal `(op (/ a b))` fuses into `_big_fdiv` for two exact integers, into **`_f64_fdiv`**
(`WasmFloatFdivRuntimeBuilder`) when a FLOAT is involved -- reading each operand as the exact
rational it is and reusing `_big_fdiv`. `_f64_fdiv` answers NULL to DECLINE (ratio operand,
non-finite float, zero divisor), falling back to `_rat_div`. The saturating `i64.trunc_sat_f64_s`
runs only under a `|d| < 2^63` guard; past it the one-argument form calls `_f64_fdiv` with divisor
one, so `(floor 1d300)` is the exact 301-digit value; a null there means a NaN or an infinity,
which signals ([[linalg-simd]], "mod / rem and the floor family").

## Ratios: exact integer components (2026-10-03)

**A ratio's numerator and denominator are exact integers in their narrowest tier, like every
other integer**, so a ratio is exact at any magnitude, as the interpreter's and the JVM's
`BigInteger` pair is. They were two i32 fields before: `(/ 3000000000 7)` printed `-184995328`,
a 17-digit decimal came back `110815915/130777088`, Scheme's `(string->number
"0.30000000000000004")` answered `0.8473649069170281` -- no trap, no warning -- and a limb-sized
component trapped. "Shared signatures" was the stated reason to keep them; it held nothing up:
the helpers moved to the callable signatures, and `TYPE_RAT_NEW`/`TYPE_RAT_GET` keep their other
users.

- `TYPE_RATIO` is `struct {eqref num, eqref den, i32 tag}`, the tag always 0: `{eqref, eqref}` is
  `TYPE_FARRAY`'s shape, and two identical singleton rec groups are ONE type to wasm-GC (the
  complex struct's trap, [[wasm-complex]]). `WasmRatioRuntimeBuilder.emitNewRatio` is the one
  construction (literals: `WasmEmitHelper.compileRatioLiteral`, constant instructions only).
  `WasmStructShapeTest` fails when two struct or array rec groups of a module coincide.
- `_rat_new(num, den)` normalizes over `_big_cmp`/`_big_neg`/`_big_gcd`/`_big_divrem`. Every level
  but `--optimize=size` opens it with an i31 head that normalizes two i31s in i32 (the binary
  helpers' precedent). `_rat_num`/`_rat_den` answer the field, an exact integer itself / 1, and
  land anything else in `_type_err_int`; `_rat_div` of two exact integers is `_rat_new(a, b)` (an
  even division demotes); cross products go through `_big_mul`, the rounding family through
  `_big_fdiv`; `_equal`/`_eql_tail` compare and `_hash` folds the components through themselves (an
  i31 component hashes to its value, so a fixnum ratio's hash is unchanged).
- **Every rational arm is guarded by `ratio(a) | ratio(b)`** (`emitEitherRatio`): after the float
  and both-exact-integer cases, an arm with no ratio operand has a non-number in it, and it lands
  through `_rat_num` in argument order without entering the computation. The guard is a type test,
  so the fold retires `_rat_new`, the `_big_*` cross products and the limb division behind the gcd
  from a module that never makes a ratio but does arithmetic on values it cannot type -- every
  Scheme and Clojure program, and CL code over list elements. Without it those modules grew by the
  limb tier (`(prn 1)` in Clojure 9,706 -> 11,047 B); with it they shrank (9,436 B).
- **`_rat_to_f64`** (`FUNC_RAT_TO_F64`) is `_as_f64`'s ratio arm, the reader's packed-literal
  coercion and the `--simd` unbox: `LispRatio.ratioToDouble`'s bits. Components within 2^53 are
  one f64 division of exact operands. Past that, e = floor(log2 |x|) from `_big_intlen` (decided
  before any division at either end of the range); a normal result takes q = floor(|x| *
  2^(62-e)) in [2^62, 2^63) with a nonzero remainder OR-ed into bit 0, so `f64.convert_i64_s`
  rounds once and the scalings by 2^-62 and 2^e are exact; a subnormal one rounds |x| * 2^1074
  half to even itself.
- **`_big_to_f64` is correctly rounded too**: the 64 bits from a limb integer's top bit down, the
  rest OR-ed into bit 0, `f64.convert_i64_u`, times 2^(L-64); past 2^1024 infinity. The top-down
  per-limb accumulation it replaced rounded once per limb: 208 of 4,500 random limb integers
  (ties and near ties weighted) printed an ulp off the interpreter's double, 0 now.
- Measured 2026-10-03, interpreter vs WASM-GC, 0 mismatches: 11,100 printed results of random
  ratio construction, arithmetic, rounding and comparison (components up to 300 bits); 7,500
  ratio-to-double conversions aimed at the subnormal and overflow edges; 4,000 exact halfway
  ties; 4,500 limb integers to double (also equal to Python's `float`). The i32 runtime missed
  nearly every line of the first three. Generators and the runner:
  `.todo/artefacts/b98-wasm-ratio-components-past-i32-answer-wrong-numbers/`.
- Size (`--optimize=default`, Preview 1, before -> after; the component and `--optimize=size`
  move the same way): a program that cannot make a ratio is byte-identical (`hello_world` 480,
  `pi_approx` 1,543, an integer `fib` 2,494, a float helper 3,405). The `bench-report` programs,
  whose footer's two-integer `round` can make one, got smaller -- `fib`/`bignum` -278, `sieve`
  -306, `clos`/`list`/`hash`/`sort`/`string` -593..-783, Scheme `collatz`/`queens` -743/-403 --
  from the guard and the shorter bodies (`_rat_div` and the rounding family hand off to
  `_rat_new` and `_big_fdiv`); `mandelbrot`/`matmul` +86/+123 (a ratio can meet a float there, so
  `_rat_to_f64` comes aboard). What costs is real ratio work: float of a computed ratio +1,529
  (`_rat_to_f64` with `_big_ash`/`_big_intlen`/`_limb_shl`/`_limb_shr`), a ratio read at run time
  and added +8,106 (21,654 -> 29,760, the limb tier behind the gcd), and a float over integers
  that can overflow +168 (the rounded `_big_to_f64`). `(/ a b)` alone went 2,685 -> 1,155: the
  fold proves the i31 head answers it and drops the `_big_*` steps.
- Speed, a 2M-iteration loop adding two fixnum ratios and comparing the sum (wasmtime 49, a loaded
  machine): 486-593 ms -> 577-685 ms at the default level (the cross products are `_big_mul` calls
  now), 822-995 -> 1,292-1,413 ms at `--optimize=size`, which has no `_rat_new` head.

## Limb division (2026-10-03)

`_limb_divrem_mag` was a bit-at-a-time loop, O(bits(u) * limbs(v)): 248 us for a 1,059-bit by
997-bit quotient, which every wide `_rat_to_f64` divides (the runtime reader's `1e-300` took 150 us).
Algorithm D is one quotient LIMB per step: 5.5 us there; a 2,000-step `gcd` of a 634-bit and a
562-bit integer 9.0 s -> 0.35 s (Euclid's quotients are short, the bit loop still walked every
dividend bit); `gen_ops.py` seed 3 1.86 -> 0.21 s. Cost: the body is 759 B against 373, so a
module that can divide limb integers grows by 386 B at every level (`bench-report` programs, whose
footer rounds two integers, +386; zlib +386; `hello_world`, an integer `fib`: 0). Inlining the
normalization shifts instead of calling `_limb_shl`/`_limb_shr`/`_limb_copy` saved 120 B in a module
that had none of them. Verified against the interpreter: 18,000 truncate/floor/ceiling/round/mod/
rem/gcd lines over operands of 1-40 limbs biased to 0/1/0x7fffffff/0x80000000/0xffffffff limbs and
near multiples (0 differences), the b98 sweeps (0), and the add-back branch made `unreachable`
traps on the first vector of `limbDivisionTakesItsCorrectionAndAddBackSteps`.

## The runtime reader's decimal floats (2026-10-03)

`WasmReadRuntimeBuilder.emitTryFloat` reads the double nearest the token, as `parseDouble` does.
It used to accumulate the digits in f64 and scale by a power built through `* 10.0`, rounding at
every step: `0.30000000000000004` read `0.3000000000000001`, `4.9e-324` read `0.0`,
`1.7976931348623158e308` `Infinity`; 2,022 of 3,000 random and halfway tokens differed. Now the
digits accumulate in an i64 below 10^17 and through `_big_grow` past it; k = exponent - fractional
digits (the exponent clamps at 10^8). A mantissa within 2^53 with |k| <= 22 is one f64 multiply or
divide of exact doubles; otherwise `%decimal-double`'s bit-length guard answers infinity/zero, the
power of ten is built by squaring (`_big_mul`; one `* 10` per digit cost 90 us for 10^300), and
`_rat_to_f64` converts `mant * 10^k` or the UNREDUCED ratio `mant / 10^-k` (it needs no gcd).
- 0 differences from the interpreter over the 3,000 tokens on Preview 1 (default and size), the
  component and the JVM.
- Speed per `read-from-string`, wasmtime, before -> after: `3.14159` and `6.02214076e23` unchanged
  (0.55 / 0.9 us); 17 digits (`0.30000000000000004`) 1.15 -> 2.2 us; extreme exponents (`1e-300`,
  `2.2250738585072014e-308`, `1.7976931348623157e308`) 0.75-2 -> 5.5-7.5 us -- 120-150 us with the
  bit-loop division.
- Size: a module that reads +748 B (`(print (read-from-string "1.5"))` 29,578 -> 30,326; 362 the
  classifier, 386 the division it now reaches).
- `5.` (digits and a final `.`) is the decimal integer on all four backends, at any magnitude, in
  `emitTryInteger` before the float classifier is reached (`.kb/read-load-streams.md`).

## Deliberate limits
- Float -> integer conversion is EXACT on all four backends (`eval/ExactRounding`, the JVM's
  `_fdiv`/`_frat`, this backend's `_f64_fdiv`); `--no-gc` cannot follow, `(floor 1d300)` traps.
  See [[linalg-simd]], "mod/rem".
- **Float-vs-exact comparison is exact on this backend too (.todo/037,
  2026-09-15)**: `_rat_cmp_bits` decomposes a finite float from its raw bits
  (hidden bit, subnormal shape, sign on the mantissa -- the `rational` shape)
  and cross-multiplies through the existing big-tier helpers alone (`_int_new`
  of the mantissa, `_big_ash`, `_big_mul`, `_big_cmp`; no new runtime function),
  so `(= 0.6666666666666666 2/3)` answers NIL here as on the interpreter and
  the JVM (2/3 sits within 2^-54 of its float -- strictly inside half an ulp,
  so no component bound could save the old f64 coercion). Both-float pairs
  keep the f64 ladder (bit-identical values compare identically); NaN stays
  unordered; infinities decide by side; a float against a non-exact operand
  keeps the old `_as_f64` behavior including its `_type_err_*` traps. The
  comparison and min/max call sites take the unboxed f64 path only when BOTH
  operands are `isDefinitelyDouble` (the JVM gate's distinction,
  `.kb/jvm-double-arithmetic.md` -- a double literal, or a `+ - * / mod rem`
  tree with a proven-double operand inside, never crossing a call or min/max,
  with any syntactically visible complex disqualifying); anything else goes
  through `_rat_cmp_bits`. Costs only what it fixes: a comparison-only module
  with no float beside an exact number is byte-identical (pure-int and
  pure-double modules measured identical; a mixed one carries `_int_new` +
  `_big_ash` + `_big_mul`, ~+1.5-1.9 KB). Pinned by
  `WasmLispCompilerIntegrationTest#floatExactComparisonNearTie` (literal,
  let-carried, branch-consumed, min/max both orders) plus a 4,412-case
  interpreter-vs-WASM-GC differential sweep with 0 mismatches. ci-spec
  `float-exact-comparison` pins a 17-digit near tie since the ratio components
  became exact (`--no-gc`, with no ratio, is no ci-spec leg).
- `isqrt` (`WasmIsqrtCompiler`) takes f64 for an i31 (or float) operand and an exact Newton
  iteration over `_big_intlen`/`_big_ash`/`_big_divrem`/`_big_add`/`_big_cmp` for the two wide
  tiers -- the f64 path trapped past 2^31 and rounded past 2^53 (2026-09-17). `random`'s
  integer path draws at most 63 bits.
- A host **u64 at or above 2^63** keeps its float-approximation lift and exact-or-trap export
  treatment ([[wit]], "The integer boundary"). `json-parse` keeps json.lisp's 18-digit rule; the
  Preview 1 `wasm-import`/`wit-import` seam narrows to `:s32`; `integer-length`/`logbitp` clamp
  indexes into the sign word.

## Index bookkeeping
`_f64_fdiv` appends after the last fixed helper (`FUNC_F64_FDIV = FUNC_ARR_UNDISPLACE + 1`,
`FX_FUNC_LAST` moves onto it), reusing `TYPE_BIG_TRIPLE`. The limb block appends after the
boxed-i64 helpers: functions `FUNC_LIMB_OF .. FUNC_BIG_FDIV`, then `FUNC_FX_VAL .. FUNC_FX_REM`
([[wasm-int-fusion]]; `FUNC_VEC_BASE`/`FUNC_USER_BASE` rebase on `FX_FUNC_LAST`); types
`TYPE_LIMBS`/`TYPE_BIGINT` (48-49), `TYPE_BIG_SHIFT`/`_TRIPLE`/`_GROW`/`_TO_F64` (50-53) after
`TYPE_PRINT_I64`, `TYPE_FX_VAL`/`_BIN`/`_DIV` (54-56); conditional `--simd`/async/instance blocks
shift past via `FX_TYPE_LAST`. `_rat_to_f64` appends after the last fixed helper too
(`FUNC_RAT_TO_F64 = FUNC_FP_HDR + 1`, `FUNC_VEC_BASE`/`FUNC_USER_BASE` after it), reusing
`TYPE_BIG_TO_F64`.

**Every module carries the limb block** -- any arithmetic can overflow into it at runtime, so it
cannot be gated statically; ~+3.8% on hello-world, `--optimize` tree-shakes the unreachable ones.
The type-test fold (`.kb/wasm-ref-type-fold.md`) then retires the TIERS a program's values never
reach -- the float and ratio arms of `_rat_*`, `_int_val`'s type-error landing -- but never the
limb promotion behind an operation that can overflow: `fib` keeps ~540 B of it, correctly.

## Tests
`Md5E2eTest` (all four), `WasmLispCompilerIntegrationTest.exactIntegersBeyondI31PromoteToBoxedI64`,
`.exactIntegersBeyondI64PromoteToLimbBigints`, `.isqrtIsExactBeyondTheI31Range`,
`.aWideIntegerIsItsOwnNumeratorOverOne`, ci-spec `exact-integers-beyond-the-i64-range`. Ratios:
`WasmLispCompilerIntegrationTest.ratioComponentsPastTheFixnumRangeStayExactAtEveryLevel`,
`.limbDivisionTakesItsCorrectionAndAddBackSteps`, `.theRuntimeReaderReadsTheNearestDoubleOfADecimalToken`
(ci-spec `runtime-read-decimal-token-is-the-nearest-double`),
`.aRatioFloatsToTheNearestDoubleTiesToEven`, `.theRuntimeReaderReadsARatioPastTheFixnumRange`,
`.rational`, `.rationalize`, `WasmRatioRuntimeBuilderTest`, `WasmStructShapeTest`, ci-spec
`ratio-components-past-the-fixnum-range` (plus the near tie in `float-exact-comparison` and the
fractional rows of `slice-c-rationalize`), scheme-spec `decimal-string-to-number-is-the-nearest-double`,
clojure-spec `read-string-reads-the-double-its-literal-is`.
