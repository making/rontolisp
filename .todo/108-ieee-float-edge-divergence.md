# 108 — IEEE-754 float edge semantics: five independent bug groups across the backends

**Status: ALL FIVE GROUPS FIXED (2026-07-10, same session as the filing).** The probe
tables below are the BEFORE state, kept as the record of what diverged; the "What was
fixed" section at the end is the ground truth now. Pre-existing and unrelated to
`--simd`; surfaced while writing todo-107's extremum kernels. `.todo/107` used to say
"`>` disagrees on `-0.0`/`NaN`" — probing showed it was far wider than `>`, and that one
of the five groups (B2) was silent wrong *arithmetic*, not an edge-value nicety.

The old ci-spec ban on float-edge cases is LIFTED: `ci-spec.yaml` now has five
`ieee-float-*` cases pinning the convergence. Still keep OUT of ci-spec the residual
forms listed at the end (variable-path `min`/`max`/`abs` edge inputs, large-float print
SHAPE beyond the one `expectedByBackend` case).

## Probe matrix (measured 2026-07-10)

`-0.0v` = `(* -1.0 0.0)`, a computed `-0.0` (needed because of B1); `NaN` = `(/ 0.0 0.0)`.
"vars" = both operands reach the operator through variables (no literal in the form), which
selects a different code path on JVM (`_cmp`) and wasm (`_rat_cmp`). wasm = Preview 1; the
component shares the same core module. `--no-gc` (probed via `:returns` exports) is
**correct on every row it can express** — static typing lowers straight to f64 opcodes.

| probe | interpreter | JVM | wasm-GC | IEEE / CL |
|---|---|---|---|---|
| `(= 0.0 -0.0v)` | **nil** | t | t | t |
| `(= -0.0v +0.0v)` vars | **nil** | t | t | t |
| `(< -0.0 0.0)` / `(> 0.0 -0.0)` | **t** | nil | nil | nil |
| `(zerop -0.0v)` | **nil** | t | t | t |
| `(minusp -0.0v)` | **t** | nil | nil | nil |
| `(funcall #'zerop -0.0v)` | t (!= direct call) | t | t | t |
| `(funcall #'minusp -0.0v)` | nil (!= direct call) | nil | nil | nil |
| `(< NaN 1.0)` literal | nil | **t** | nil | nil |
| `(<= NaN 1.0)` literal | nil | **t** | nil | nil |
| `(<= NaN one)` vars | nil | **t** | **t** | nil |
| `(>= NaN one)` vars | **t** | nil | **t** | nil |
| `(> NaN 1.0)` | **t** | nil | nil | nil |
| `(= NaN NaN)` literal | **t** | nil | nil | nil |
| `(= NaN NaN)` vars | **t** | nil | **t** | nil |
| `(/= NaN NaN)` | **nil** | t | **nil** | t |
| `(min 1.0 NaN)` | **1.0** | NaN | (print traps) | impl-defined |
| `(max NaN 1.0)` | NaN | NaN | (print traps) | impl-defined |
| `(sort (list 0.0 -0.0v) #'<)` | (-0.0 0.0) | (0.0 -0.0 order kept) | printer hides it | ties keep order |

| producer probe | interpreter | JVM | wasm-GC | correct |
|---|---|---|---|---|
| `(- 5.0)` | -5.0 | **5.0** | **5.0** | -5.0 |
| `(- (* 2.0 3.0))` | -6.0 | **6.0** | **6.0** | -6.0 |
| `(- 5)` / `(- x)` x a double var | -5 / ok | -5 / ok | -5 / ok | ok |
| `(print -0.0)` literal | -0.0 | **0.0** | **0.0** | -0.0 |
| `(print (* -1.0 0.0))` | -0.0 | -0.0 | **0.0** | -0.0 |
| `(/ 1.0 0.0)` as a VALUE | Infinity | Infinity | Infinity (comparisons work) | Infinity |
| `(print (/ 1.0 0.0))` | Infinity | Infinity | **trap: integer overflow** | prints Infinity |
| `(print NaN)` | NaN | NaN | **trap: invalid conversion** | prints NaN |

## Group A — the interpreter compares doubles in a total order

`Environment.compareNumeric` (src/main/java/am/ik/rontolisp/eval/Environment.java:3575),
double branch = `Integer.signum(Double.compare(a, b))`. `Double.compare` is Java's TOTAL
order: `-0.0 < 0.0`, `NaN == NaN`, NaN above `+Infinity`. Consumers:

- `compareChain` (3593) behind `=` `<` `>` `<=` `>=` (env.define at 1404-1412), so also
  `sort`/`remove-if`/... through `#'<` (the first-class wrapper re-enters `compareChain`).
- `min` (1113), `max` (1123).
- `zerop`/`plusp`/`minusp` — the eval path expands them (LispEvaluator.java:1100-1104) via
  `LispMacroExpander.expandZerop/Plusp/Minusp` (2292/2302/2312) to `(= x 0)`/`(> x 0)`/
  `(< x 0)`, so they inherit A on the interpreter (and are correct on JVM/wasm, whose
  `=`/`<`/`>` handle `-0.0` right).
- `/=` = `expandNumericNotEqual` (3377) → `(not (= ...))`, inherits everywhere.

Two aggravations. (1) The interpreter disagrees WITH ITSELF: `#'zerop`/`#'minusp` resolve to
`Environment`'s LispFunction definitions (3059/3084), which use plain Java `== 0.0`/`< 0.0`
(IEEE), so `(zerop -0.0v)` → nil but `(funcall #'zerop -0.0v)` → t. (2) `compareChain`
decides "sign within [loSign, hiSign]", a 3-value design that cannot express NaN's
*unordered* — the return-value design of `compareNumeric` must change, not just its math.
The integer/ratio/bignum branches are exact and stay untouched.

## Group B — the compilers' double-literal fast path mis-lowers two producers

`hasDoubleLiteral` matches a double literal ANYWHERE in the form, recursively — so these hit
`(- (f x 2.0))` just as hard as `(- 5.0)`.

- **B2 (the severe one): unary `(- x)` compiles to IDENTITY on both compilers** when the
  operand form contains a float literal. `(- 5.0)` → 5.0, `(- (* 2.0 3.0))` → 6.0. The
  double-literal path of `JvmArithCompiler.compile` handles unary `/` (reciprocal) but has
  no unary `-` case, so the "loop from arg 2" simply never negates; only the NON-literal
  path (line 61-65) dispatches to the `_neg` runtime helper (DNEG,
  JvmNumericRuntimeBuilder.java:637 — IEEE-correct). `WasmArithCompiler.compile` has the
  same shape (unary `/` special-cased, unary `-` falls through to identity). Fix = add the
  unary-negation case (DNEG / `f64.neg`) to both double paths. The repo never noticed
  because everything (ci-spec's sigmoid `(- 0 x)`, the webgl examples' `(- 0.0 x)`) uses
  the binary idiom.
- **B1: the JVM eats the `-0.0` literal.** `JvmEmitHelper.compileDouble`
  (JvmEmitHelper.java:30) peepholes `value == 0.0` → `DCONST_0`, and `-0.0 == 0.0` in Java.
  Fix = guard with `Double.doubleToRawLongBits(value) == 0L`, exactly as
  `JvmQuoteCompiler.emitRawDouble` (JvmQuoteCompiler.java:140) already does for packed-array
  data. The wasm literal is fine (`F64_CONST` writes raw bits); only its printer hides the
  sign (group C).

## Group C — the wasm float printer (same root as `.todo/046`)

`WasmRuntimeBuilder.buildPrintF64Core` (WasmRuntimeBuilder.java:2820):

- sign: `is_neg = value < 0.0` (`f64.lt`) — false for `-0.0`, so the sign vanishes in
  printing (the VALUE is right: `(* -1.0 0.0)` exported under `--no-gc` prints `-0`).
  Fix = test the sign bit (`i64.reinterpret_f64` < 0).
- integer part: `f64.floor` + `i32.trunc_s/f64` — traps on |x| >= 2^31 (that is
  **`.todo/046`** verbatim), on ±Infinity ("integer overflow") and on NaN ("invalid
  conversion to integer"). Infinity/NaN need explicit textual output; digit extraction
  cannot represent them at any width.

Division is NOT broken: `(> (/ 1.0 0.0) 1.0e9)` → t on wasm. Fix C together with
`.todo/046` (i64 or exponent normalization + Infinity/NaN text + sign bit). Note:
`WasmLispCompilerIntegrationTest.java:5927` prints an `:initial-element -0.0` array — its
expected output may pin today's sign loss; recheck when fixing.

## Group D — JVM: DCMPL for all five comparison operators

`JvmComparisonCompiler` (JvmComparisonCompiler.java:25) emits `DCMPL` for `=` `<` `>` `<=`
`>=` alike, and the shared `_cmp` runtime helper does the same
(JvmNumericRuntimeBuilder.buildCmp:843 → emitDoubleCmpPrologue:1600, DCMPL at 1617). DCMPL
yields -1 for unordered, so with a NaN operand `<` and `<=` answer **t** while `=`/`>`/`>=`
are accidentally right. javac's own rule: DCMPG for `<`/`<=`, DCMPL for `>`/`>=`, so NaN
always falls out false. The literal path can pick the opcode per operator; `_cmp` (also
behind `min`/`max` n-ary reduction? audit its callers) needs the group-A redesign or per-op
helpers. `-0.0` is fine on the JVM comparison side.

## Group E — wasm: variable-path comparisons funnel through a signum `_rat_cmp`

`WasmComparisonCompiler` non-literal path calls `FUNC_RAT_CMP` and applies the operator
against 0. `buildRatCmpBody` (WasmRatioRuntimeBuilder.java:368)'s float branch (377) is
`(a > b) - (a < b)` — NaN → `0 - 0` = 0 = "equal". So through variables, NaN `=`/`<=`/`>=`
→ **t** and `/=` → **nil**; and since `expandNumericNotEqual` binds temps, `/=` ALWAYS takes
the variable path — wasm `/=` can never agree with wasm literal `=` on NaN. wasm `=` is
therefore path-dependent: `(= (/ 0.0 0.0) (/ 0.0 0.0))` → nil but the same through a `let`
→ t. `-0.0` is fine here (0 → equal is the right answer for `=`). The literal path
(per-operator `f64.eq/lt/gt/le/ge`) is fully IEEE and is the model to follow.

## The shared design flaw behind A, D, E

Three backends, three 3-valued comparison funnels, three DIFFERENT wrong NaN signums:
interpreter `Double.compare` (total order), JVM `DCMPL` (unordered → -1), wasm
`(a>b)-(a<b)` (unordered → 0). None can express IEEE's fourth relation, *unordered*. The
fix must either add that state to each funnel (e.g. a sentinel the chain maps to nil for
all of `=` `<` `>` `<=` `>=`, and to t only for `/=`), or dissolve the funnel into per-
operator IEEE predicates the way the wasm literal path (and all of `--no-gc`) already
works. Target semantics = what the wasm literal path and `--no-gc` do today: `-0.0 = 0.0`
→ t (CLHS: they are `=` but not `eql`); NaN: `=` `<` `>` `<=` `>=` → nil, `/=` → t;
`min`/`max` on ±0.0 → either (keep-first is fine); `min`/`max` with NaN → pick a policy and
document it (JVM/wasm propagate NaN today via Math.min / `f64.min`; JvmMinCompiler /
WasmMinCompiler on the binary path, `expandReduction` pairwise otherwise).

## `--simd` kernel lockstep — MUST land in the same change as group A

The three extremum-kernel implementations deliberately mirror THEIR OWN backend's scalar
comparison (`.kb/linalg-simd.md`, "`>` disagrees across backends already"): interpreter
`eval/LinalgSimdKernels` uses `Double.compare` (lines 306-383, rationale at 297), JVM
`JvmSimdVectorTemplate` plain Java `>` (~498), wasm `WasmLinalgSimdRuntimeBuilder` `f64.gt`
(~368). Fixing A makes the interpreter's `>` IEEE, so **`LinalgSimdKernels` must switch
from `Double.compare` to plain `>`/`<` in the same commit** — the tripwire is already in
place: `LinalgSimdTest` 207-211 asserts `(linalg:amax #d(-0.0 0.0))` and `#d(0.0 -0.0)`
match the scalar oracle. JVM/wasm kernels already match their backends and need no change.
`linalg.lisp` / `vec.lisp` themselves stay untouched (the oracle rule).

## What already depends on today's behavior (surveyed 2026-07-10)

Nothing pins the wrong values; three places to keep in mind:

- `LinalgSimdTest:207-211` pins kernel == defun on `-0.0` (survives A iff kernel and
  interpreter move together — that is the point of the lockstep section).
- `WasmLispCompilerIntegrationTest:5919/5927` prints a `-0.0`-filled array on wasm — its
  expectation likely encodes the group-C sign loss; update alongside C.
- `LispLexerTest:155` (`-2d-3` → -0.002) is reader-level and unaffected.
- Libraries (`json.lisp`/`url.lisp`/`linalg.lisp`/`vec.lisp`): no unary minus over
  float-literal forms (negation is written `(- 0 det)` — linalg.lisp:418); unaffected by B2.
- ci-spec.yaml: zero NaN/Infinity/`-0.0`/unary-float-minus cases. DocExamplesTest runs the
  interpreter only, so compiler-side fixes are invisible to it.

## What was fixed (2026-07-10), in the planned order, failing-test-first

- **B2**: both double-literal paths gained the unary-negation case — `JvmArithCompiler`
  (DNEG) and `WasmArithCompiler` (`f64.neg`). `(- 5.0)` → -5.0, `(- (* 2.0 3.0))` → -6.0,
  `(- 0.0)` → -0.0 everywhere.
- **B1**: `JvmEmitHelper.compileDouble` got the `doubleToRawLongBits` guard (mirroring
  `emitRawDouble`), so the `-0.0` literal survives; `(/ 1.0 -0.0)` → -Infinity on JVM.
- **A**: `Environment.compareNumeric`'s double branch is IEEE (`<`/`>`/`==` with
  `UNORDERED = 2` for NaN); `compareChain` rejects UNORDERED via the existing window
  check (2 falls outside every `[loSign, hiSign]`), so `=` `<` `>` `<=` `>=` are nil on
  NaN and `/=` is t. `min`/`max` follow Math.min/Math.max: NaN propagates, a 0.0/-0.0
  tie resolves by sign (`isNaN`/`isNegativeZero` helpers). `zerop`/`minusp`/`plusp` and
  their `#'` function values now agree. **Lockstep**: `LinalgSimdKernels` switched
  `Double.compare` → plain `>`/`<` in the same change; `LinalgSimdTest` 22/22 green.
- **D**: `JvmComparisonCompiler` literal path picks DCMPG for `<`/`<=`, DCMPL otherwise
  (javac's rule); the no-literal path calls the new `_cmpb` runtime helper
  (`JvmNumericRuntimeBuilder.buildCmpBits`: bitmask 1=lt, 2=eq, 4=gt, 0=unordered;
  non-doubles delegate to `_cmp` and map via `1 << (signum+1)`), then masks and IFNEs.
- **E**: new emitted function `_rat_cmp_bits` (`FUNC_RAT_CMP_BITS`, right after
  `FUNC_RAT_CMP`; `WasmRatioRuntimeBuilder.buildRatCmpBitsBody`): float branch is
  lt→1 / gt→4 / eq→2 / else 0, exact branch maps `_rat_cmp` via `i32.shl`.
  `WasmComparisonCompiler`'s no-literal path masks it. The eval runtime inherits the
  fix (its builtin dispatch compiles through the same comparison compiler).
- **C** (with `.todo/046`): `buildPrintF64Core` rewritten — NaN/Infinity print as text
  (early return), the sign comes from `i64.reinterpret_f64` (so `-0.0` keeps it), the
  integer part uses the historical i32 path below 2^31 and an i64 MSD digit loop up to
  2^63 (pow built as `1e9 * 1e9`; the writer's LEB is 32-bit), and >= 2^63 normalizes
  into [1,10) and appends `E<exp>`. `StringTable` gained `NaN`/`Infinity`/`E`.

Tests: 12 new unit tests (JvmLispCompilerTest x4, LispEvaluatorTest x4 — written first,
all red, then green), 4 new WasmLispCompilerIntegrationTest methods (Docker wasmtime),
5 new ci-spec cases (`ieee-float-*`; large-magnitude uses `expectedByBackend: wasm`).
Verified: full suite 3072/0 (incl. the 619+ Docker wasm integration tests), manual
18-probe program byte-identical on interpreter / interpreter `--simd` / JVM / wasm P1 /
wasm component (sole difference: the documented 1.5E12-vs-1500000000000.0 shape),
`--no-gc` probes unchanged-correct, `-Pweb compile`, javadoc (known `Version` error
only), native `CiSpecE2eTest` + `ExamplesE2eTest`, `DocExamplesTest`.
Docs: `doc/{en,ja}/compiling/wasm.md` known-limitation paragraph rewritten;
`.kb/json.md` + `.kb/linalg-simd.md` (the "`>` disagrees" section is now "`>` agrees").
Bonus finding while probing: `(read-from-string "-0.0")` → -0.0 on ALL backends — the
runtime readers preserve the sign, no fix needed there.

## Residuals (deliberately not fixed; CL-permitted or cosmetic)

- **Large-float print SHAPE** stays split: 10^7..2^63 wasm prints all digits where
  interpreter/JVM print `1.5E12`; >= 2^63 wasm is approximate (`/10` rounds). Full
  shortest-round-trip E-notation parity is what remains of `.todo/046`.
- **Variable-path `min`/`max`** off the double-literal fast path: JVM `_min`/`_max` are
  `buildSelect` over `_cmp` (not Math.min) and wasm's are a `_rat_cmp` select — NaN does
  not propagate and a ±0.0 tie picks by position there. CLHS allows either; keep such
  forms out of ci-spec.
- **wasm `abs` via variables** (`WasmAbsCompiler`'s `_rat_cmp` select): `(abs x)` with
  x = -0.0 stays -0.0 (Math.abs gives +0.0 elsewhere). Same CL-latitude bucket.
- **`eql`/`equal` on -0.0 vs 0.0** across backends unaudited (CLHS: `=` but not `eql`).
