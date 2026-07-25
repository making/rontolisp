# Arbitrary-precision exact integers on the wasm-GC backends

Retire the signed-64-bit ceiling: an exact integer must be exact at any
magnitude on the interpreter, the JVM AND both wasm-GC backends (Preview 1 and
`--component`), as Common Lisp requires. Today only the first two are.

Prerequisite of `.todo/175` (SCRAM-SHA-256), but NOT specific to it: the same
ceiling is what makes ironclad's `public-key` + `math` subsystems unreachable
(`.kb/asdf.md`), and it is a plain CL conformance gap.

## Measured state (2026-07-25)

`.kb/wasm-bignum.md` is the full mechanics. Two representations today, with a
hard normalization invariant:

- **i31 fixnum** for `[-2^30, 2^30-1]`.
- **`TYPE_BIGNUM`** — a `struct {i64}`, the module's only `{i64}` struct so
  `ref.test` discriminates it — for anything outside that range.
- Invariant: an in-range integer is ALWAYS an i31. `_int_new (i64) -> eqref`
  (`FUNC_INT_NEW`) is the sole producer and demotes on the way out;
  `_int_val (eqref) -> i64` widens either form. `ref.eq` therefore stays a valid
  fixnum equality fast path.

Both failure modes are reproducible today:

```lisp
(defvar *a* #xba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad)
(print (logxor *a* *a*))
```

- Preview 1 / `--component`: **compile error at the literal** —
  `UnsupportedOperationException: Cannot compile: 8434...9965`
  (`WasmExprCompiler.compileExpr`), i.e. a 256-bit constant has no
  representation at all.
- Values that DO fit i64 wrap silently past `2^63` (documented as "one range up
  from the old i31 wrap").
- Interpreter and JVM: correct (`LispBigInteger`). Verified the ops SCRAM needs
  — `ash`, `ldb (byte 8 248)`, `ldb (byte 256 0)`, `integer-length`, `logxor` —
  all exact on both.

## Design direction (to confirm before coding)

Add a THIRD tier rather than replacing the box: `i31` → `TYPE_BIGNUM {i64}` →
a new limb representation (sign + `(array (mut i64))`, little-endian limbs).
Keep the invariant generalized to "always the narrowest tier that holds the
value", so `ref.eq`/`eql`/`_hash` keep working and every existing dispatch site
that already routes through `_int_new`/`_int_val` keeps compiling — those two
functions become the tier boundary, with `_int_val` signalling (or a paired
`_big_p`) when a value no longer fits i64.

Work list (each item is a site that currently assumes "exact = i64"):

1. **Type + index bookkeeping**: the new type entry and runtime functions append
   like the bignum block did (`BIGNUM_TYPE_LAST` and the `FUNC_*_BASE` rebasing
   are the existing seam).
2. **Literals**: `WasmEmitHelper.compileIntegerLiteral` +
   `WasmQuoteCompiler` — a limb array built at instantiation (data segment) or
   on first use.
3. **Arithmetic**: add/sub/mul/truncating-div/rem — `WasmRatioRuntimeBuilder`'s
   `_rat_add/_sub/_mul/_rem/_mod/_div/_cmp` both-exact-int fast paths.
4. **Bitwise**: `WasmBitwiseCompiler` (`logand/logior/logxor/lognot/ash/
   integer-length/logbitp`, and via the macro lowerings `ldb`/`dpb`/
   `logandc1`-family). `ash` currently clamps shift magnitudes at 63.
5. **Comparison / ordering**: `_rat_cmp` → `_rat_cmp_bits` feeds
   `= < > <= >=`, `min`/`max`, `abs`, `signum`, `sort`.
6. **Printing**: `_print_val`/`_princ_val` → `_print_i64_no_nl` needs a decimal
   conversion by repeated division (and `princ-to-string`/`format` capture).
7. **Reading**: `_rd_radix` + the decimal classifier in `_read_expr` accumulate
   in i64 today, so the emitted reader must match the frontend
   (`.kb/read-load-streams.md`).
8. **Float conversion**: `WasmEmitHelper.castFloatGetF64` /
   `emitLocalToF64` / `WasmIntConvCompiler` (`truncate/floor/ceiling/round`).
9. **The number library**: `gcd`/`lcm` (`WasmMathHelper.emitEuclid`), `expt`
   (loops `_rat_mul`), `isqrt` (a known residue), `random`'s integer path.
10. **Equality/hash**: `_equal`, `_hash`, `eql`/`eq` boxed-value comparison.
11. **Ratio components are still i32** — the documented residue: `TYPE_RAT_NEW`/
    `TYPE_RAT_GET` signatures are shared by a dozen unrelated runtime functions,
    so `_rat_num`/`_rat_den` WRAP a bignum to i32 today. Decide explicitly:
    widen (touches everything) or keep wrapping and document that big ratios
    stay inexact. `(floor big 16)` in a single-value context goes through this
    path.
12. **The boundaries stay exact-or-trap**: `WasmExportCompiler`'s s64/u64 cases
    and `WasmComponentImportCompiler.boxI64`/`lowerI64` — a declared `s64` is a
    promise, so a value past its range must keep trapping rather than silently
    widening (`.kb/wit.md`).
13. **json-parse**'s 18-digit exactness rule (json.lisp `%json-number`) can widen.

## Acceptance

- A ci-spec case over 256-bit and 512-bit values (literal, `logxor`, `ldb`,
  `integer-length`, `ash`, print, `read-from-string` round-trip, `truncate`,
  `gcd`) byte-identical on ALL FOUR backends, run through the native
  `CiSpecE2eTest`.
- `WasmLispCompilerIntegrationTest.exactIntegersBeyondI31PromoteToBoxedI64`
  extended (it currently PINS the i64 tier).
- `.kb/wasm-bignum.md` rewritten: the three tiers, the generalized invariant,
  and a fresh "deliberate limits" section (whatever of item 11 survives).

## Risks

- **One emitted function body must not grow without bound**
  (`.kb/wasm-function-body-size.md`): the limb loops belong in runtime
  functions, not inlined at call sites.
- Performance: every integer op gains a tier test. Keep the i31 fast path first
  and the i64 path second, exactly as today.
- Module size: the runtime block is emitted in EVERY module (like the reader
  helpers), so a program with no big integer pays for it — measure, and gate the
  limb block on the program actually needing it if the cost shows.

## Non-goals

- `--no-gc` (no GC heap, no allocation for limbs; it is i64-native by design and
  documented as such).
- Bignum-component ratios, if item 11 lands as "keep wrapping".
