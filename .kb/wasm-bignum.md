# WASM exact integers: the three-tier representation

wasm-GC (Preview 1 AND `--component`; `--no-gc` is i64-native, unaffected) holds an exact
integer in exactly one of three tiers:

- **i31 fixnum** — `[-2^30, 2^30-1]`.
- **`TYPE_BIGNUM`** — `struct {i64}`, own rec group, the only `{i64}` struct in the module
  so `ref.test` discriminates it. Outside i31, inside signed 64-bit.
- **`TYPE_BIGINT`** — `struct {(ref null $limbs)}` over **`TYPE_LIMBS`** (`array (mut i32)`),
  two's-complement little-endian 32-bit limbs, beyond signed 64-bit, exact at any magnitude.
  Shares one rec group with TYPE_LIMBS; the intra-group typed field reference keeps the pair
  structurally unique under wasm-GC canonicalization.

**Normalization invariant: a value always lives in the NARROWEST tier that holds it.** So
`ref.eq` stays a valid fixnum equality fast path, and equal integers are always the same
tier (i31 by `ref.eq`, TYPE_BIGNUM by i64 field, TYPE_BIGINT by `_big_eq` — wired into
eq/eql/`_equal`/`_hash`).

- `_int_new (i64) -> eqref` (`WasmBignumRuntimeBuilder`) produces/demotes the i64 tier.
  `_int_val (eqref) -> i64` widens the two narrow tiers and TRAPS (explicit `unreachable`)
  on TYPE_BIGINT — what keeps every boundary exact-or-trap. A NON-number lands in the
  catchable `_type_err_int` (`.kb/error-handling.md`).
- `_limb_new` (`WasmBigIntRuntimeBuilder`) canonicalizes: strips redundant sign-extension
  top limbs, hands anything that fits to `_int_new`. A TYPE_BIGINT therefore has >= 3 limbs.
- Literals split the same way (`WasmEmitHelper.compileIntegerLiteral`,
  `compileBigIntegerLiteral` via `array.new_fixed`, **capped at 10000 limbs**), as does the
  emitted reader (`_rd_radix`, `_read_expr`'s decimal classifier, stepping an eqref
  accumulator through `_big_grow`), so `read`/`read-from-string` match the frontend.

## Runtime (`WasmBigIntRuntimeBuilder`), all `(ref null eq)` in/out

**`_limb_*`**, raw arrays: `_limb_of` (widen any tier; TYPE_BIGINT answers its OWN array —
read-only, `_limb_copy` before mutating), `_limb_new`, `_limb_get` (sign-extends past the
top), `_limb_addsub`, `_limb_neg`, `_limb_mul` (schoolbook; **limbs are 32-bit because a
limb product must fit an i64 — core wasm has no widening 64-bit multiply**), `_limb_cmp`,
`_limb_shl`/`_limb_shr`, `_limb_divrem_mag` (binary long division on magnitudes),
`_limb_divmod_small` (in-place, the printer's 10^9 chunker).

**`_big_*`**, dispatching all three tiers with an i64 fast path FIRST: `_big_add`/`_big_sub`
(overflow-checked, promote not wrap), `_big_mul`, `_big_neg`, `_big_divrem` (truncating;
traps on zero divisor; i64.min/-1 routes to limbs), `_big_mod`, `_big_fdiv` (exact
truncate/floor/ceiling/round-ties-even), `_big_cmp`, `_big_and/or/xor/not`, `_big_ash`
(**left shift past 2^25 bits traps as an allocation guard**), `_big_intlen`, `_big_logbitp`,
`_big_gcd`, `_big_grow`, `_big_to_f64`, `_big_print`/`_big_print_mag`/`_big_pad9`
(divmod-10^9 through `_write_str`, so no buffer limits the digits), `_big_eq`, `_big_hash`.

## Tier-aware dispatch sites

- `WasmRatioRuntimeBuilder`: `_rat_add/_sub/_mul` -> `_big_add/_sub/_mul`;
  `_rat_rem`/`_rat_mod` -> `_big_divrem`/`_big_mod`; `_rat_cmp` -> `_big_cmp` (feeding
  `_rat_cmp_bits`, hence `= < > <= >=`, `min`/`max`, `abs`, `signum`, `sort`); `_rat_div`'s
  even-division check -> `_big_divrem`; `emitIsExactInt` tests all three; `emitLocalToF64`
  and `WasmEmitHelper.castFloatGetF64` -> `_big_to_f64`.
- `WasmBitwiseCompiler` -> the `_big_*` bitwise helpers (`ldb`/`dpb`/`logandc1`-family
  inherit the range via their `LispMacroExpander` lowerings).
- `WasmIntConvCompiler` (`truncate/floor/ceiling/round`): exact-int identity first; a literal
  `(op (/ a b))` fuses into `_big_fdiv` for two exact integers, and into **`_f64_fdiv`**
  (`WasmFloatFdivRuntimeBuilder`) when a FLOAT is involved — that reads each operand as the
  exact rational it is (a finite double is `mantissa * 2^exponent`, trailing zeros stripped;
  an integer is itself over one), cross-multiplies via `_big_mul` and reuses `_big_fdiv`. It
  answers NULL to DECLINE (ratio operand, non-finite float, zero divisor), falling back to
  `_rat_div` + generic conversion. The generic float path's saturating `i64.trunc_sat_f64_s`
  runs only under a `|d| < 2^63` guard; past it the one-argument form calls `_f64_fdiv` with
  divisor one, so `(floor 1d300)` is the exact 301-digit value.
- `WasmGcdCompiler` -> `_big_gcd`; `WasmLcmCompiler` composes
  `_big_mul`/`_big_divrem`/`_big_gcd`/`_big_neg`; `expt` promotes by looping `_rat_mul`.
- `_print_val`/`_princ_val` -> `_print_i64_no_nl` / `_big_print` (`emitPrintBignum`).
  `integerp`/`numberp`/`rationalp` test `i31 | TYPE_BIGNUM | TYPE_BIGINT`. The emitted `eval`
  treats all three as self-evaluating (`WasmEvalRuntimeBuilder`; JVM twin in
  `JvmEvalRuntimeBuilder`).

## Deliberate limits

- **Ratio components stay i32.** `TYPE_RAT_NEW`/`TYPE_RAT_GET` are shared by a dozen
  unrelated runtime functions (`_gensym`, `_str_build`, `_hash`, ...). `_rat_num`/`_rat_den`
  **wrap** a TYPE_BIGNUM to i32 and **trap** on TYPE_BIGINT (the `_int_val` cast): an UNEVEN
  `/` over a limb integer, and mixed limb-integer x ratio arithmetic, have no representation.
  Reachable gap: a user-level `(/ big 3)` kept as a fraction.
- **Float -> integer conversion is EXACT on all four backends** (`eval/ExactRounding`, the
  JVM's `_fdiv`/`_frat`, this backend's `_f64_fdiv`; second value off `rem`/`mod` in the
  shared `LispMacroExpander` lowering). `--no-gc` cannot follow — `(floor 1d300)` traps.
  Reasoning and pins: `.kb/linalg-simd.md`, "mod/rem".
- **`_big_to_f64` accumulates top-down per limb**, possibly differing from
  `BigInteger.doubleValue()` in the last ulp — keep limb-integer -> float out of ci-spec.
- **`isqrt` goes through f64** (`WasmIsqrtCompiler`, `trunc(sqrt((f64) x))`): exact on the
  i31 range only, diverging from the interpreter's exact integer square root. `random`'s
  integer path draws at most 63 bits; a limb-sized limit traps via `_int_val`.
- A host **u64 at or above 2^63** keeps its float-approximation lift and exact-or-trap
  export treatment (`.kb/wit.md`, "The integer boundary"): a limb value reaching a declared
  `s64`/`u64` traps rather than silently widening.
- `json-parse` keeps json.lisp's 18-digit exactness rule (in the library, every backend).
  `--no-gc` stays i64-native by design; the Preview 1 `wasm-import`/`wit-import` seam still
  narrows to `:s32`. `integer-length`/`logbitp` clamp indexes into the sign word.

## Index bookkeeping

`_f64_fdiv` appends after the last fixed helper (`FUNC_F64_FDIV = FUNC_ARR_UNDISPLACE + 1`,
`FX_FUNC_LAST` moves onto it) and reuses `TYPE_BIG_TRIPLE`, so no index above it and no type
entry moves. The limb block appends after the boxed-i64 helpers: functions
`FUNC_LIMB_OF .. FUNC_BIG_FDIV`, then fusion helpers `FUNC_FX_VAL .. FUNC_FX_REM`
(`.kb/wasm-int-fusion.md`; `FUNC_VEC_BASE`/`FUNC_USER_BASE` rebase on `FX_FUNC_LAST`); types
`TYPE_LIMBS`/`TYPE_BIGINT` (one rec group, 48-49),
`TYPE_BIG_SHIFT`/`TYPE_BIG_TRIPLE`/`TYPE_BIG_GROW`/`TYPE_BIG_TO_F64` (50-53) after
`TYPE_PRINT_I64`, and `TYPE_FX_VAL`/`TYPE_FX_BIN`/`TYPE_FX_DIV` (54-56); the conditional
`--simd`/async/instance blocks shift past via `FX_TYPE_LAST`. Others reuse
`TYPE_CALLABLE_BASE`(+1), `TYPE_RAT_CMP`, `TYPE_RAT_GET`, `TYPE_STR_TO_MEM`,
`TYPE_PRINT_VAL`, `TYPE_PRINT_I32`.

**Every module carries the limb block** — any arithmetic can overflow into it at runtime, so
it cannot be gated statically; ~+3.8% on hello-world, and `--optimize` tree-shakes the
unreachable limb functions. The saturating-truncation opcode joined `WasmTreeShaker`'s
enumerated decoder as the `0xFC` misc prefix.

## Pinning tests

`Md5E2eTest` (all four backends),
`WasmLispCompilerIntegrationTest.exactIntegersBeyondI31PromoteToBoxedI64`,
`.exactIntegersBeyondI64PromoteToLimbBigints`, ci-spec `exact-integers-beyond-the-i64-range`.
