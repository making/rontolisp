# JVM integer expression-tree fusion (outlined `_fx$N` methods)

**Invariant: fusing an integer expression tree must never change a result, an observable side
effect, or an error shape -- the fast path is an optimization with a total fallback.**

JVM analogue of `.kb/wasm-int-fusion.md`, integer sibling of `.kb/jvm-typed-loops.md`. A nested
tree over `+ - * mod rem logand logior logxor lognot ash` (plus `1+`/`1-`, normalized to `+`/`-`
with a constant 1) becomes ONE unboxed evaluation: non-constant leaves evaluated once, left to
right; interior raw `long`; only the root boxes. It removes an allocation rate, not arithmetic.

## Outlined, not inline
A fused site is its own private static `_fx$N`; the call site is one `invokestatic` over the
once-evaluated leaves. A fused site emits its tree TWICE (fast + fallback), so inlining would cross
the 8000-byte `HugeMethodLimit` (`.kb/hot-path-method-size.md`). Structurally identical sites share
one method (`State.byKey`) -- unless an operation reports a source site other than the call's (a
tree spanning lines, an inlined defun's body): then the key carries the sites and the fallback marks
each operation's -- and the prologue a `random` draw's, around the `_random` call that may reject
its limit -- so the uncaught report names the operation's line and function
([error-handling.md](error-handling.md)); a one-line tree stays shared and line-free. Inside the
method the operand stack is the tree's own, so an overflow
bail through the `ArithmeticException` handler (which discards the stack) cannot disturb an
enclosing expression's pending operands.

## How exactness survives the raw path
- **Per-leaf guard**: each leaf arrives as `Object`, unboxed behind `instanceof Long`. An integer
  in `long` range is always a `Long` here (`.kb/core-representation.md`), so that one test is the
  whole tier check.
- **Per-operation overflow check**: `Math.addExact`/`subtractExact`/`multiplyExact`,
  `Math.floorMod` (`mod`), `LREM` (`rem`), the emitted `_fxAsh(JJ)J` (`ash`, count narrowed to
  `int` first). The fast path sits in an `ArithmeticException` region whose handler IS the bail.
- **The fallback recomputes the WHOLE tree from the SAME parameters** through `_add`-family,
  `_logand`, `_ash`, `_cmpb` -- identical bit for bit incl. `BigInteger` promotion; leaf side
  effects ran once at the call site.
- Exact strength reductions: `(mod x 2^k)` positive power-of-two literal -> `x & (2^k - 1)`;
  `(ash x -k)` literal non-positive count -> arithmetic right shift clamped at 63.
- **Masked-wrap peephole**: under a non-negative literal `logand` mask or a power-of-two `mod`, the
  `+ - *`/left-`ash`-by-literal subtree emits as UNCHECKED wrap-around `long` ops.
- **Masked signed field**: `(%mask-signed-field k x)` (SBCL's `sb-c::mask-signed-field`, a `cl`
  internal) with a literal `1 <= k <= 64` is an op node over `(k, x)`: `x` emits WRAPPED as under a
  mask, and a field narrower than 64 sign-extends (`LSHL`/`LSHR` by `64 - k`). So
  `(%mask-signed-field 64 (* a b))` is one `LMUL` -- the Clojure unchecked verbs' wrap
  (`.kb/clojure-frontend.md`). The fallback is the lowering's arm
  (`LispMacroExpander.expandMaskSignedField`: `logxor` with `-2^(k-1)`, `logand` with `2^k - 1`,
  `+` of `-2^(k-1)`) through `_logxor`/`_logand`/`_add`, so a non-integer reports as `LOGXOR`
  exactly as the unfused lowering and the interpreter's builtin do. Over a lone leaf it declines
  (`hasConstOperand` does not count the size): the site would only re-box. Any other size is a
  leaf the lowering owns. Pinned by
  `JvmLispCompilerTest.aMaskedSignedFieldOverAProductMultipliesInOneUncheckedLong` (an `LMUL` in a
  fused method; default and size levels print SBCL's values, `testsupport/MaskSignedFieldProgram`).

## The interpreter's order
The call evaluates every leaf before the method applies anything, which is the
interpreter's order only where nothing between an application and a later leaf can tell
(`.kb/argument-evaluation-order.md`, "An operation applies after its operands"). Until
2026-10-07 nothing checked: `(+ (* 2 a) (progn (princ "x") 1))` printed before `*` signalled,
`(+ (aref v 0) (progn (setf (aref v 0) 99) 1))` read the stored 99, an exit leaf left
before `*` could signal, and `(+ (* 2 a) (car x))` reported `car`.

- **Events.** Classification records, in the interpreter's order, each leaf's evaluation
  and each application -- an operation, an aref read, a draw -- with the caller-level form
  it came from (`Site.events`, `Site.origins`; an inlined body's applications follow its
  arguments, as a call's do).
- **Boundaries** (`planOrder`). Before a leaf whose evaluation is observable (not a
  constant or a quiet variable) while applications are pending, the call site runs a check:
  - `PendingGuard`, when every pending application is an operation whose divisor or shift
    count is a literal or a leaf: the leaves they read are `Long`s, the divisors non-zero,
    the counts no larger than an `int` -- then they cannot signal. The pending set clears.
  - `LeafGuard`, when the leaf is integer arithmetic over quiet variables the tree did not
    take (`(- x)`, a one-operand `+`): those variables are `Long`s -- then the leaf cannot
    signal or change anything and the applications stay pending; an aref read keeps its
    raw read in the method. An arithmetic aref index, its main case until 2026-10-07, is
    part of the tree now (below).
  - Anything else -- an aref read before a leaf that may store, a `random` draw, a
    `mod`/`rem`/`ash` over a computed divisor or count -- is made opaque: an ordinary leaf
    evaluated in place by the ordinary emission (`classifyOrdered` repeats the
    classification until nothing more is asked; `Ordering.expansions` keeps an `ldb`
    expansion's conses stable across repeats; a site whose root is asked declines).
- **A failed check calls the probe** `_fxp$N` (void, outlined and shared by structure like
  `_fx$N`): the pending applications through the generic helpers under their own
  operators and sites, values dropped. One that signals signals there; otherwise the leaf
  runs and the fused method later answers through its fallback.
- **Emission.** A site with no boundary pushes its leaves as before. With one, every leaf
  goes into a temporary in order, each boundary's check runs before its leaf, and the
  temporaries are pushed at the end. A site whose checks would run more than
  `MAX_ORDER_CHECKS` leaf tests (nested operations completing in front of one observable
  leaf after another re-check their leaves at each) declines fusion.

Why this shape: the checks are the fused method's own guards (`instanceof Long`, a zero
divisor, a shift count), so a passing check is work the method repeats and the JIT shares
once both are inlined, and a temporary is no machine work; the generic helpers run only
when a check fails. Rejected: cutting the tree at each observable leaf (a box per cut, the
allocation fusion exists to remove), calling a probe that recomputes the pending subtree
raw on every evaluation (duplicate arithmetic on the hot path), making every pending aref
read opaque (a boxed read in UTF-8 and MD5 loops), and declining fusion for such trees.
Census 2026-10-07 (scratch instrumentation of the JVM classification, 248 programs of
`examples/`, `bench-report` and `size-report`, shared library code counted once per
program, every global counted as observable): 159,287 fused sites, 4,737 with an
application pending before an observable leaf -- 5,765 integer operations, 1,002 `ash` by
a literal, 1,158 by a leaf count and 341 by a computed one (all `ldb`/`dpb` over a
run-time byte spec), 31 `mod` by a literal, 502 aref reads (mostly in front of an
arithmetic aref index: UTF-8 decoding, MD5 -- which no longer is a leaf), no draw and no
computed divisor.

## Entry points beyond plain trees
- **Fused comparisons** (`= < > <= >=`, binary): `_fx$N` returning a raw `int`; generic
  `_cmpb`-with-mask on the fallback. **Condition position** (`if`/`while`, hence
  `when`/`unless`/`dotimes`/`loop` heads) branches with `IFEQ`, no boxed round trip per iteration.
  Two plain leaves stay generic.
- **Substitution** of a fusion-inlinable defun (`Ctx.inlinableDefuns`, computed before Pass 2,
  never under `--dynamic`) or a let-bound local function (`Ctx.localIntLambdas`, `JvmLetCompiler`),
  so a tree spans `mod32+`/`rol32`/`sigma0`. A parameter used twice SHARES its argument's node; a
  failed substitution rolls back the leaves it registered. A body that is exactly `(aref P I)` maps
  onto an ArefLeaf.
- **Packed aref leaves** (whenever `Ctx.usesArrays`): a rank-1 `(aref a i)` reads raw from either
  the bare `long[]` packed integer vector (elements from slot 1, past the width header; only under
  `Ctx.usesIntArray`) or the general array's length-6 header over a flat `long[]` (elements from
  slot 0, `.kb/adjustable-arrays.md`). Discriminator: `instanceof ArrayList`, `size() != 0`,
  `get(0) instanceof Object[]`, `length == 6` -- **4 is a character vector, 5 a displacement, 3 the
  boxed general array**. The nil sentinel (`Long.MIN_VALUE`), an out-of-range index (a long one past the int range
  too, which no truncation may turn into a read) and every
  non-packed shape bail into the same `_ivAref1`/`_fvAref1`/`_arrayAref1` the ordinary emission
  would use. **The INDEX is an operand of the tree** (`arefLeaf`): its leaves register after
  the array's and its operations apply before the read, in the interpreter's order, so
  `(aref v (+ i 1))` puts no leaf (and no check) between the reads in front of it. The
  prologue computes an operation index raw (`emitArefRead`) inside the checked region, which
  then opens before the reads, so an overflow bails; an aref in another's index is read first
  (`emitArefReads`); the fallback computes it generically. Measured 2026-10-07 (scratch
  kernels copying `flexi-streams:octets-to-string`'s decode and md5's `fill-block-ub8` loop over
  1 MB, best of 15 rounds per process, load 20-50): steady state unchanged (decode 84-95 ms,
  fill 42-46 ms either way -- C2 already scalar-replaced the index's box once it inlined the
  index's own `_fx$N`); the first round, through tier-up, decode median 266 -> 221 ms and
  fill 279 -> 218 ms (8 processes each); the library calls themselves within noise; a class
  loading flexi-streams and md5 196,069 -> 194,710 B. The WASM half gains in steady state
  (`.kb/wasm-int-fusion.md`).
- **Random leaves**: `(random <integer>)` draws with the same formula `_random` uses for a `Long`
  limit, `(long) (ThreadLocalRandom.current().nextDouble() * limit)` (`.kb/random.md`). **The only
  IMPURE leaf, and its protocol follows**: the fallback re-emits its tree and a shared parameter
  node re-emits twice, so a drawing fallback would make `(dif (random lim))` over
  `(defun dif (x) (- x x))` stop answering 0. The draw happens exactly once per leaf, in the
  prologue, on every path; the fallback only READS it. A non-`Long` limit draws once through
  `_random` into a boxed slot and raises the shared bail flag, tested ONCE after all draws.
- **Unboxed dual-representation locals** (`RawLocal`): raw `long` slot + boxed shadow + `int` flag.
  Eligible = plain lexical, not special, not captured (`FreeVarAnalyzer.findCapturedVars`, asked by
  `JvmLetCompiler` BEFORE `rawBindingEligible`), not a promoted global, not a duplicate in its
  `let`, body defines no nested `defun`, REASSIGNS an integer-shaped value, and neither init nor
  assignment is float-contaminated (`.kb/jvm-double-arithmetic.md`). Traps: **null cannot be the
  raw marker** (a local assigned nil must read back as nil), so a flag, not a sentinel -- which
  also keeps a static field and `<clinit>` out; **all three slots are pre-initialized at the
  binding**, or a later `LLOAD` fails verification. Stores go through `JvmSetqCompiler` ->
  `JvmIntFusionCompiler.compileRawStore` (dispatching on the RESULT type), reads elsewhere through
  `_ubRead(Object, long, int)`; `MAX_RAW_ASSIGN_SITES` / `MAX_LET_BODY_ASSIGN_SITES` cap the
  per-site bytes. A name in `Ctx.rawLocals` is never in `Ctx.locals`.
- **Unboxed promoted GLOBALS** (`Ctx.rawGlobals`, `JvmRawGlobals`): the same triple as CLASS FIELDS
  -- `_gr$X`, `_gk$X` beside the boxed shadow `_g$X`, so a non-raw store is byte-for-byte the
  unfused `putstatic`. Eligibility is program-wide and narrow: fusion on, not `--dynamic`, NO eval
  runtime (`_genv` holds the box), nothing concurrent (a non-volatile `long` may tear), never
  DYNAMICALLY bound, not a compiler-internal cell (`%MV-SPILL`, the stream specials), at least one
  integer assignment, few enough sites. A LEXICAL binding still wins at every site
  (`JvmIntFusionCompiler.resolveRaw`). Three emissions know the representation:
  `JvmExprCompiler.compileSpecialRead`, `JvmSetqCompiler`, `JvmDefvarCompiler`.
- **The counted-loop STEP is emitted INLINE, ahead of the outlined method**
  (`emitRawStepFastPath`), guarded by the source's flag and by `Math.addExact`'s overflow condition
  spelled out for the constant addend; exactly two declines branch into the outlined call (a stale
  raw slot, an overflowing step). What it buys is the LAYOUT of what the loop builds -- C2
  scalar-replaces a dead box only once it COMPILES the loop, and a 1000-iteration loop never
  reaches an OSR threshold. Pinned by
  `JvmLispCompilerTest.aCountedLoopStepPromotesAtTheFixnumBoundaryAndKeepsSteppingOnABignum`.

## When fusion does NOT trigger (and must keep not triggering)
- `--optimize=size` (`Ctx.intFusion`, the `!prefersSizeOverSpeed()` gate) and `--dynamic`;
  `-Drontolisp.debug.nointfusion=true` force-disables at COMPILE time. Off, every site falls
  through byte-identically.
- A single fusable op with neither a raw-reading leaf nor a literal operand; more than 64 ops or 32
  leaves; division (`/`, exact ratios); a comparison over two plain leaves; a constant-folded root.
- A node `JvmLispCompiler.hasDoubleLiteral` claims, and an immediate `BigInteger`/ratio literal. A
  taken tree whose leaves turn out to be `Double`s does NOT bail: the method carries a second
  all-Double fast path (`.kb/jvm-double-arithmetic.md`).
- **Other integer-valued built-ins do not earn a leaf** (measured): `char-code`, `elt` on a packed
  vector, `length` (whose answer IS a three-way dispatch).

## Mechanics
The prologue runs in FOUR passes, because a leaf's raw value can be another leaf's input: random
slots pre-set, then the draws (before any guard, so a bail always finds the value drawn), then the
`ExprLeaf`/`RawLeaf` guards, last the aref reads. `JvmIntFusionCompiler` hooks into
`JvmExprCompiler` (arithmetic/bitwise/comparison, `funcall`, `compileSymbolRef`),
`JvmSetqCompiler`, `JvmIfCompiler`/`JvmWhileCompiler`, `JvmLetCompiler`. The shared `State` holds
pending methods, the dedup map and the lazily-minted `_ubSentinel`/`_ubRead`/`_fxAsh`; Pass 2d
emits the pending bodies (they compile no Lisp, so the list cannot grow under the walk). A program
with no fused site and no raw local is byte-identical to before.

## Not this mechanism's fault
A captured `let` variable assigned INLINE in a sibling branch failing with
`class java.lang.Long cannot be cast to class [Ljava/lang/Object;` is the ONE-BYTE local index
limit (`.kb/jvm-method-size-limits.md`) -- past 255 slots `astore 256/257/258` truncates to
`astore 0/1/2`. It reproduces with fusion off.

## Pinning tests
`JvmLispCompilerTest.fusedIntegerExpressionTreesMatchTheGenericPath`,
`.fusedArefLeavesReadTheGeneralArraysPackedShapeAndBailForEveryOther`,
`.anArithmeticArefIndexIsComputedInsideTheFusedTree` (one `_fx$` call and no probe per site),
`.unboxedTopLevelGlobalsAnswerWhatTheBoxedStaticFieldAnswers`,
`.aDynamicallyBoundSpecialAndAnEvaldGlobalDeclineTheUnboxedRepresentation` (pinned by the ABSENCE
of the `_gr$` field too), `.theSizeLevelChangesNothingWithoutASpeedForSizeTrade`;
`JvmLibraryMethodSizeTest`; ci-spec `fused-integer-expression-trees`,
`flet-fusion-and-unboxed-locals`, `fused-comparisons-and-raw-leaf-stores`,
`fused-random-and-aref-leaves`. The order: `FastPathEvaluationOrderFixture`
(`.compileAndRunFastPathsKeepTheInterpretersEvaluationOrder`, both levels) and ci-spec
`fast-paths-keep-the-evaluation-order`.

## Unfinished
- The store dispatch re-boxes a raw local's fast-path value at every assignment; a raw-returning
  `(...)J` variant with its own bail protocol is next.
- Params are not eligible for the dual representation (they arrive boxed by signature).
- `%aset` values fuse boxed (`_ivAset1` still takes a boxed operand).
- A program touching `eval`, threads, an http handler, async or sockets keeps the boxed static
  field for EVERY global; the concurrency half is a conservative reading, not a measured problem.
