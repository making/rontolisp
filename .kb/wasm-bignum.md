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
- **`_big_*`** dispatch all three tiers with an i64 fast path FIRST: `_add`/`_sub`
  (overflow-checked, promote not wrap), `_mul`, `_neg`, `_divrem` (truncating, traps on zero
  divisor), `_mod`, `_fdiv` (truncate/floor/ceiling/round-ties-even), `_cmp`, `_and/_or/_xor/_not`,
  `_ash` (**left shift past 2^25 bits traps as an allocation guard**), `_intlen`, `_logbitp`,
  `_gcd`, `_grow`, `_to_f64`, `_print`/`_print_mag`/`_pad9`, `_eq`, `_hash`.

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
one, so `(floor 1d300)` is the exact 301-digit value.

## Deliberate limits
- **Ratio components stay i32**: `_rat_num`/`_rat_den` **wrap** TYPE_BIGNUM to i32 and **trap** on
  TYPE_BIGINT, because `TYPE_RAT_NEW`/`TYPE_RAT_GET` are shared by a dozen unrelated runtime
  functions. Reachable gap: a user-level `(/ big 3)` kept as a fraction.
- Float -> integer conversion is EXACT on all four backends (`eval/ExactRounding`, the JVM's
  `_fdiv`/`_frat`, this backend's `_f64_fdiv`); `--no-gc` cannot follow, `(floor 1d300)` traps.
  See [[linalg-simd]], "mod/rem".
- `_big_to_f64` accumulates top-down per limb, possibly differing from `BigInteger.doubleValue()`
  in the last ulp -- keep limb-integer -> float out of ci-spec.
- `isqrt` goes through f64 (`WasmIsqrtCompiler`), exact on the i31 range only, diverging from the
  interpreter. `random`'s integer path draws at most 63 bits.
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
shift past via `FX_TYPE_LAST`.

**Every module carries the limb block** -- any arithmetic can overflow into it at runtime, so it
cannot be gated statically; ~+3.8% on hello-world, `--optimize` tree-shakes the unreachable ones.

## Tests
`Md5E2eTest` (all four), `WasmLispCompilerIntegrationTest.exactIntegersBeyondI31PromoteToBoxedI64`,
`.exactIntegersBeyondI64PromoteToLimbBigints`, ci-spec `exact-integers-beyond-the-i64-range`.
