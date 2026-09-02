# WASM exact integers: the three-tier representation

The wasm-GC backend (Preview 1 AND `--component`; `--no-gc` is i64-native and
unaffected) represents an exact integer in one of exactly three ways:

- **i31 fixnum** — every integer in `[-2^30, 2^30-1]`, as before.
- **`TYPE_BIGNUM`** — a `struct {i64}` (its own rec group, the only `{i64}` struct
  in the module, so `ref.test` discriminates it) holding an integer outside the
  i31 range but inside the signed 64-bit range.
- **`TYPE_BIGINT`** — a `struct {(ref null $limbs)}` over **`TYPE_LIMBS`**
  (`array (mut i32)`): two's-complement little-endian 32-bit limbs, for anything
  outside the signed 64-bit range. The two types share one rec group; the
  intra-group typed field reference keeps the pair structurally unique under
  wasm-GC canonicalization. Exact at any magnitude, as Common Lisp requires —
  this is what carries SCRAM-SHA-256's 256-bit working state and ironclad's
  `public-key`/`math` arithmetic.

**The normalization invariant: a value always lives in the NARROWEST tier that
holds it.** `_int_new (i64) -> eqref` (`WasmBignumRuntimeBuilder`) remains the
i64-tier producer/demoter and `_int_val (eqref) -> i64` widens the two narrow
tiers (it still TRAPS on a `TYPE_BIGINT` — now an explicit `unreachable`, which is
what keeps every boundary exact-or-trap — while a NON-number lands in the catchable
`_type_err_int` signal instead, todo-592, `.kb/error-handling.md`); `_limb_new` (`WasmBigIntRuntimeBuilder`) canonicalizes a
limb array — strips redundant sign-extension top limbs, hands anything that fits
back to `_int_new` — so a `TYPE_BIGINT` always has >= 3 canonical limbs. Because
of the invariant, `ref.eq` stays a valid fixnum equality fast path, and two
numerically equal integers are always the same tier (i31 by ref.eq; TYPE_BIGNUM
by i64 field; TYPE_BIGINT by `_big_eq` limb compare — wired into
eq/eql/`_equal`/`_hash`). Compile-time literals follow the same split
(`WasmEmitHelper.compileIntegerLiteral` for the i64 range,
`compileBigIntegerLiteral` emitting the canonical limbs via `array.new_fixed`,
capped at 10000 limbs), as does the emitted runtime reader (`_rd_radix` and the
decimal classifier in `_read_expr` step an eqref accumulator through
`_big_grow`), so `read`/`read-from-string` match the frontend at any magnitude.

## The runtime (WasmBigIntRuntimeBuilder)

Two layers, all `(ref null eq)` in/out so every pre-existing call shape survives:

- **`_limb_*`** operate on raw limb arrays: `_limb_of` (widen any tier to limbs;
  a TYPE_BIGINT answers its own array — read-only, `_limb_copy` before
  mutating), `_limb_new`, `_limb_get` (sign-extends past the top),
  `_limb_addsub`, `_limb_neg`, `_limb_mul` (schoolbook over 32-bit limbs — a
  limb product fits an i64, which is WHY limbs are 32-bit; core wasm has no
  widening 64-bit multiply — plus the two's-complement sign corrections),
  `_limb_cmp`, `_limb_shl`/`_limb_shr`, `_limb_divrem_mag` (binary long
  division on magnitudes, O(bits x limbs) — plenty for crypto-sized values),
  `_limb_divmod_small` (in-place, the decimal printer's 10^9 chunker).
- **`_big_*`** dispatch across all three tiers with an i64 fast path FIRST, so
  fixnum arithmetic costs what it did: `_big_add`/`_big_sub` (overflow-checked
  i64, promoting instead of wrapping), `_big_mul` (clz-guarded), `_big_neg`
  (i64.min promotes), `_big_divrem` (truncating; traps on zero divisor; routes
  the i64.min/-1 edge to limbs), `_big_mod`, `_big_fdiv` (exact
  truncate/floor/ceiling/round-ties-even division), `_big_cmp`, `_big_and/or/
  xor/not` (two's complement makes CL negative-operand bitwise semantics plain
  limb-wise ops), `_big_ash` (left shift past 2^25 bits traps as an allocation
  guard), `_big_intlen`, `_big_logbitp`, `_big_gcd`, `_big_grow` (the reader
  accumulator step), `_big_to_f64`, `_big_print`/`_big_print_mag`/`_big_pad9`
  (recursive divmod-10^9 decimal renderer through `_write_str`, so
  princ-to-string/format capture works and no fixed buffer limits the digits),
  `_big_eq`, `_big_hash`.

Dispatch sites that know about the tiers:

- `WasmRatioRuntimeBuilder`: the both-exact-int arms of `_rat_add/_sub/_mul`
  call `_big_add/_sub/_mul`; `_rat_rem/_rat_mod` call `_big_divrem`/`_big_mod`;
  `_rat_cmp` calls `_big_cmp` (feeding `_rat_cmp_bits`, so `= < > <= >=`,
  `min`/`max`, `abs`, `signum`, `sort` follow); `_rat_div`'s even-division check
  runs through `_big_divrem`; `emitIsExactInt` tests all three tiers (so the
  `_rat_trunc`-family identity guard covers TYPE_BIGINT); `emitLocalToF64` and
  `WasmEmitHelper.castFloatGetF64` convert a TYPE_BIGINT via `_big_to_f64`.
- `WasmBitwiseCompiler` emits plain calls to the `_big_*` bitwise helpers
  (`ldb`/`dpb`/`logandc1`-family arrive via their `LispMacroExpander` lowerings
  and inherit the range).
- `WasmIntConvCompiler` (`truncate/floor/ceiling/round`): exact-int identity
  first; a literal `(op (/ a b))` shape — which is what the two-argument
  `(truncate a b)` family lowers to, single-value AND multiple-value — fuses
  into `_big_fdiv` when both operands are exact integers, because the ratio
  intermediate cannot hold limb components, and into **`_f64_fdiv`**
  (`WasmFloatFdivRuntimeBuilder`, todo-660) when a FLOAT is involved: that helper
  reads each operand as the exact rational it is (a finite double is
  `mantissa * 2^exponent` exactly, with the mantissa's trailing zeros stripped;
  an exact integer is itself over one), cross-multiplies through `_big_mul` and
  hands the pair to the same `_big_fdiv`, so the four rounding modes stay in one
  place. It answers a NULL to decline -- a ratio operand, a non-finite float, a
  zero divisor -- and the site falls back to `_rat_div` plus the generic
  conversion. The generic float path still narrows with the SATURATING
  `i64.trunc_sat_f64_s`, but only under a `|d| < 2^63` guard (exact there: every
  double past 2^52 is already an integer); past it the one-argument form calls
  `_f64_fdiv` with a divisor of one, so `(floor 1d300)` is that double's exact
  301-digit value rather than Long.MAX_VALUE.
- `WasmGcdCompiler` -> `_big_gcd`; `WasmLcmCompiler` composes
  `_big_mul`/`_big_divrem`/`_big_gcd`/`_big_neg`; `expt` promotes automatically
  (it loops `_rat_mul`).
- Printing: `_print_val`/`_princ_val` route TYPE_BIGNUM to `_print_i64_no_nl`
  and TYPE_BIGINT to `_big_print` (`emitPrintBignum`).
- Predicates: `integerp`/`numberp`/`rationalp` test `i31 | TYPE_BIGNUM |
  TYPE_BIGINT`.
- The emitted `eval` treats all three integer tiers as self-evaluating
  (`WasmEvalRuntimeBuilder`; the JVM's emitted eval gained the matching
  `BigInteger` arm in `JvmEvalRuntimeBuilder`).

Pinning tests: `Md5E2eTest` (all four backends),
`WasmLispCompilerIntegrationTest.exactIntegersBeyondI31PromoteToBoxedI64` (the
i64 tier) and `.exactIntegersBeyondI64PromoteToLimbBigints` (the limb tier), and
the `exact-integers-beyond-the-i64-range` ci-spec case.

## Deliberate limits (the "why", so the next visitor can re-evaluate)

- **Ratio components stay i32** (`TYPE_RATIO` unchanged): `TYPE_RAT_NEW`/
  `TYPE_RAT_GET` signatures are shared by a dozen unrelated runtime functions
  (`_gensym`, `_str_build`, `_hash`, ...), so widening them touches everything.
  `_rat_num`/`_rat_den` **wrap** a TYPE_BIGNUM operand to i32 (pre-limb
  truncating semantics) and **trap** on a TYPE_BIGINT (the `_int_val` cast):
  an UNEVEN `/` over a limb integer and mixed limb-integer x ratio arithmetic
  have no representation. Even division is exact via `_big_divrem`, and the
  whole floor-family divides exactly through the `_big_fdiv` fusion, so the
  reachable gap is a user-level `(/ big 3)` kept as a fraction. Revisit if a
  real library needs exact big ratios.
- **Float -> integer conversion is EXACT on all four backends since todo-660**
  (2026-09-02) -- it used to clamp at the long range everywhere
  (`(truncate 1e30)` = `Long.MAX_VALUE`), which also made the remainder beside it
  the dividend. The cross-backend change landed: `eval/ExactRounding`, the JVM's
  `_fdiv`/`_frat` and this backend's `_f64_fdiv`, with the second value read off
  `rem`/`mod` in the shared `LispMacroExpander` lowering. `--no-gc` is the one
  backend that cannot follow (no bignum tier; `(floor 1d300)` traps there). Full
  reasoning, the SBCL disagreement and the pinning tests: `.kb/linalg-simd.md`,
  "mod/rem".
- **`_big_to_f64` accumulates top-down per limb**, which may differ from a
  correctly-rounded `BigInteger.doubleValue()` in the last ulp — keep
  limb-integer -> float conversions out of ci-spec expectations.
- **`isqrt` computes through f64** (`WasmIsqrtCompiler`,
  `trunc(sqrt((f64) x))`): exact on the i31 range, only approximate for wider
  operands (diverging from the interpreter's exact integer square root) — the
  long-standing residue. **`random`'s integer path** draws at most 63 bits; a
  limb-sized limit traps via `_int_val`.
- A host-supplied **u64 at or above 2^63** keeps its float-approximation lift
  and exact-or-trap export treatment (`.kb/wit.md` "The integer boundary") —
  the boundary types are a declared promise, so a limb value reaching a
  declared `s64`/`u64` traps via `_int_val` rather than silently widening.
- **`json-parse`** keeps the shared json.lisp 18-digit exactness rule (the rule
  lives in the library, identical on every backend).
- The **`--no-gc`** backend stays i64-native by design (no GC heap to hold
  limbs) and is documented as such. The Preview 1 `wasm-import`/`wit-import`
  seam still narrows to `:s32` (`.todo/169`).
- The `_big_ash` left-shift cap (2^25 bits) bounds a runaway `(ash 1 huge)`
  allocation; `integer-length`/`logbitp` clamp indexes into the sign word.

## Index bookkeeping

`_f64_fdiv` is appended after the last fixed helper (`FUNC_F64_FDIV =
FUNC_ARR_UNDISPLACE + 1`, and `FX_FUNC_LAST` moves onto it) and reuses
`TYPE_BIG_TRIPLE`, `_big_fdiv`'s own signature, so no index above it and no type
entry moves.

The limb block appends after the boxed-i64 helpers exactly like the bignum block
did: functions `FUNC_LIMB_OF .. FUNC_BIG_FDIV`, then the unboxed-fixnum fusion
helpers `FUNC_FX_VAL .. FUNC_FX_REM` (`.kb/wasm-int-fusion.md`; then
`FUNC_VEC_BASE`/`FUNC_USER_BASE` rebase on `FX_FUNC_LAST`), types
`TYPE_LIMBS`/`TYPE_BIGINT` (one rec group, 48-49) plus the four helper
signatures (`TYPE_BIG_SHIFT`/`TYPE_BIG_TRIPLE`/`TYPE_BIG_GROW`/
`TYPE_BIG_TO_F64`, 50-53) after `TYPE_PRINT_I64` and the three fusion
signatures (`TYPE_FX_VAL`/`TYPE_FX_BIN`/`TYPE_FX_DIV`, 54-56); the conditional
`--simd`/async/instance blocks shift past them via `FX_TYPE_LAST`. Every
module carries the block (arithmetic on ANY module can overflow into the limb
tier at runtime), like the reader helpers; the remaining signatures reuse
existing type entries (`TYPE_CALLABLE_BASE`(+1), `TYPE_RAT_CMP`, `TYPE_RAT_GET`,
`TYPE_STR_TO_MEM`, `TYPE_PRINT_VAL`, `TYPE_PRINT_I32`).

Measured cost of the always-present block (hello-world, 2026-07-25): Preview 1
189,481 -> 196,608 bytes (+3.8%), component +7.1 KB likewise, `--optimize`
20,843 -> 21,952 (the tree-shaker drops the unreachable limb functions). Small
enough that gating the block on "program uses big integers" (impossible to
know statically anyway — any arithmetic can overflow at runtime) stays not
worth it. The saturating-truncation opcode joined `WasmTreeShaker`'s enumerated
decoder as the `0xFC` misc prefix.
