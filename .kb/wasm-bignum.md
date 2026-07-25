# WASM boxed exact integers (the i31 overflow path)

The wasm-GC backend (Preview 1 AND `--component`; `--no-gc` is already i64-native
and unaffected) represents an exact integer in one of exactly two ways:

- **i31 fixnum** — every integer in `[-2^30, 2^30-1]`, as before.
- **`TYPE_BIGNUM`** — a `struct {i64}` (its own rec group, the only `{i64}` struct
  in the module, so `ref.test` discriminates it) holding an integer OUTSIDE that
  range.

**The normalization invariant: an in-range integer is ALWAYS an i31, never a box.**
`_int_new (i64) -> eqref` (`FUNC_INT_NEW`, `WasmBignumRuntimeBuilder`) is the sole
producer and demotes on the way out; `_int_val (eqref) -> i64` (`FUNC_INT_VAL`)
widens either representation. Because of the invariant, `ref.eq` stays a valid
equality fast path for fixnums, and two numerically equal integers are either both
i31 (ref.eq) or both boxed (compared by field — wired into eql/`equal`/`eq`/`_hash`;
the interpreter's `eq` compares value types with `equals`, so `(eq #x100000000
#x100000000)` answers `T` on every backend). Compile-time literals follow the same
split (`WasmEmitHelper.compileIntegerLiteral`, used by `WasmExprCompiler` and
`WasmQuoteCompiler`), as does the emitted runtime reader (`_rd_radix` and the
decimal classifier in `_read_expr` accumulate in i64 and finish through
`_int_new`), so `(read-from-string "#xEFCDAB89")` matches the frontend.

## Semantics

Integer arithmetic on the GC backend is **exact through the full signed 64-bit
range and wraps beyond it** (no arbitrary-precision promotion; the interpreter
and JVM stay exact via `LispBigInteger`). What this unlocks: unsigned 32-bit
working state — md5's `#xEFCDAB89` magic constants and `(ldb (byte 32 0) ...)`
sums — and 64-bit integer columns (cl-postgres int8/OID). Pinning tests:
`Md5E2eTest` (all four backends) and
`WasmLispCompilerIntegrationTest.exactIntegersBeyondI31PromoteToBoxedI64`.

Dispatch sites that know about the box:

- `WasmRatioRuntimeBuilder`: `_rat_add/_sub/_mul` compute a both-exact-int fast
  path in i64 → `_int_new` (so i31 overflow promotes and a shrinking result
  demotes); `_rat_rem/_rat_mod` likewise; `_rat_cmp` compares both-int operands
  directly in i64 (feeding `_rat_cmp_bits`, so `= < > <= >=`, `min`/`max`, `abs`,
  `signum` and `sort` follow); `_rat_trunc/_floor/_ceil/_round` return an exact
  integer unchanged (identity guard); `_rat_div` divides evenly-divisible int
  pairs in i64.
- `WasmBitwiseCompiler`: `logand/logior/logxor/lognot/ash/integer-length/logbitp`
  all unbox through `_int_val`, compute in i64, re-normalize through `_int_new`.
  `ash`/`logbitp` clamp a right-shift magnitude at 63 (wasm shifts mask mod 64).
  `ldb`/`dpb`/`logandc1`-family arrive here via their `LispMacroExpander`
  lowerings.
- `WasmEmitHelper.castFloatGetF64` + the ratio runtime's `emitLocalToF64`: a boxed
  integer converts via `f64.convert_i64_s`, so `float`, `sqrt`, float contagion
  and the float comparison paths accept it.
- `WasmIntConvCompiler` (`truncate/floor/ceiling/round`): exact-int identity
  first; the float path truncates to **i64** and re-normalizes (a float past the
  i31 range converts to a box, not a trap).
- Printing: `_print_val`/`_princ_val` route the box to `_print_i64_no_nl`
  (`FUNC_PRINT_I64_NO_NL`), which funnels through `_write_str` so
  `princ-to-string`/`format` capture works.
- Predicates: `integerp`/`numberp`/`rationalp` test `i31 | TYPE_BIGNUM`.
- `_equal` and `_hash` (`WasmRuntimeBuilder`): both-boxed value equality; the
  hash folds the i64 halves (mirrors the float branch).

## Widened consumers (post-md5 sweep)

- **`gcd`/`lcm`** compute in i64 (`WasmGcdCompiler`/`WasmLcmCompiler` over
  `WasmMathHelper.getI64/setI64/emitEuclid`), **`random`'s integer path** draws
  63 bits and takes the remainder in i64 (`WasmRandomCompiler.emitRandomI63`) —
  all three accept and return the full exact-integer range. Pinned by the
  `gcdLcm`/`randomOnABoxedIntegerLimit` integration tests and the
  `exact-integers-beyond-the-i31-fixnum-range` ci-spec case.
- **The time built-ins return exact integers** (`WasmTimeCompiler` → `_int_new`),
  not the pre-bignum float — `.kb/time-environment-builtins.md`.
- **The export boundary carries the whole fixed-width family, 64-bit included**
  (`WasmExportCompiler`: `emitBoxWideInt` and the s64/u64 param/result cases go
  through `_int_new`/`_int_val`; a u64 at or above 2^63 traps, exact-or-trap),
  and a `--component` import lifts a wide integer into the box
  (`WasmComponentImportCompiler.boxI64`) and lowers one exactly
  (`lowerI64`) — `.kb/wit.md` "The integer boundary".
- **`json-parse`** keeps integers up to 18 digits exact (json.lisp
  `%json-number`), retiring the 9-digit float rule.

## Deliberate limits (the "why", so the next visitor can re-evaluate)

- **Ratio components stay i32** (`TYPE_RATIO` unchanged): `TYPE_RAT_NEW`/
  `TYPE_RAT_GET` signatures are shared by a dozen unrelated runtime functions
  (`_gensym`, `_str_build`, `_hash`, ...), so widening them touches everything.
  Instead `_rat_num`/`_rat_den` **wrap** a bignum operand to i32 (pre-bignum
  truncating semantics, not a trap): mixed bignum×ratio arithmetic and an
  UNEVEN `/` of a bignum (e.g. `(floor big 16)` in a single-value context, which
  lowers to `(floor (/ big 16))`) are inexact past i31. Even division is exact
  via the `_rat_div` i64 fast path. Revisit if a real library needs exact big
  ratios.
- **`expt`** promotes automatically (it loops `_rat_mul`), exact to i64.
- Products/sums past 2^63 wrap silently — same class as the old i31 wrap, one
  range up. `most-positive-fixnum` semantics are unchanged.
- A host-supplied **u64 at or above 2^63** has no exact place in the signed i64
  box: an import lift degrades it to the float approximation (u64::MAX is a
  common "no limit" sentinel, so trapping would break real hosts); an export
  wrapper traps instead, because there the declared type is a promise the
  program makes (`.kb/wit.md`).
- The **`--no-gc`** backend and the **JVM/interpreter** are untouched. The
  Preview 1 `wasm-import`/`wit-import` seam still narrows to `:s32`
  (`.todo/169`).

## Index bookkeeping

The three runtime functions append after the reader block
(`FUNC_INT_NEW`/`FUNC_INT_VAL`/`FUNC_PRINT_I64_NO_NL`, then
`FUNC_VEC_BASE`/`FUNC_USER_BASE` rebase on them); the four type entries
(`TYPE_BIGNUM`/`TYPE_INT_NEW`/`TYPE_INT_VAL`/`TYPE_PRINT_I64`, 44-47) append
after `TYPE_RD_MEMEQ`, and the conditional `--simd`/async/instance blocks shift
past them via `BIGNUM_TYPE_LAST` — every module (with or without a bignum in the
program) carries them, like the reader helpers.
