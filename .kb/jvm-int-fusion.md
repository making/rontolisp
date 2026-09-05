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
one method (`State.byKey`). Inside the method the operand stack is the tree's own, so an overflow
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
  boxed general array**. The nil sentinel (`Long.MIN_VALUE`), an out-of-range index and every
  non-packed shape bail into the same `_ivAref1`/`_fvAref1`/`_arrayAref1` the ordinary emission
  would use. The INDEX is itself a fusion node.
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
`.unboxedTopLevelGlobalsAnswerWhatTheBoxedStaticFieldAnswers`,
`.aDynamicallyBoundSpecialAndAnEvaldGlobalDeclineTheUnboxedRepresentation` (pinned by the ABSENCE
of the `_gr$` field too), `.theSizeLevelChangesNothingWithoutASpeedForSizeTrade`;
`JvmLibraryMethodSizeTest`; ci-spec `fused-integer-expression-trees`,
`flet-fusion-and-unboxed-locals`, `fused-comparisons-and-raw-leaf-stores`,
`fused-random-and-aref-leaves`.

## Unfinished
- The store dispatch re-boxes a raw local's fast-path value at every assignment; a raw-returning
  `(...)J` variant with its own bail protocol is next.
- Params are not eligible for the dual representation (they arrive boxed by signature).
- `%aset` values fuse boxed (`_ivAset1` still takes a boxed operand).
- A program touching `eval`, threads, an http handler, async or sockets keeps the boxed static
  field for EVERY global; the concurrency half is a conservative reading, not a measured problem.
