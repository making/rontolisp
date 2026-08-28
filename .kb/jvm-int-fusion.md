# JVM integer expression-tree fusion (outlined `_fx$N` methods)

**Invariant: fusing an integer expression tree must never change a result, an
observable side effect, or an error shape -- the fast path is an optimization
with a total fallback, not a semantic variant.** Introduced by `.todo/412`
(2026-08-26), the JVM analogue of the wasm backend's fusion
(`.kb/wasm-int-fusion.md`) and the integer sibling of the typed float loops
(`.kb/jvm-typed-loops.md`). A nested arithmetic/bitwise tree over
`+ - * mod rem logand logior logxor lognot ash` (plus `1+`/`1-`, normalized into
`+`/`-` with a constant 1) compiles into ONE unboxed evaluation: the non-constant
leaves are evaluated once, left to right, and the interior stays raw `long`;
only the root boxes. Without this every interior operation paid a generic-helper
call (`_add(Object, Object)`-family) plus a `Long` allocation per intermediate
value -- 551 of 600 JFR allocation samples on ironclad's PBKDF2 were
`java.lang.Long`, and the measured cost was the allocation rate, not the
arithmetic.

## Outlined, not inline -- and why

Unlike the wasm version, a fused site is its OWN private static method
(`_fx$N`), and the call site is one `invokestatic` over the once-evaluated
leaves. Three reasons:

- **Method size.** A fused site emits its tree twice (fast + fallback), and
  ironclad's `update-sha256-block` sat at the 8000-byte `HugeMethodLimit`
  (`.kb/hot-path-method-size.md`): 7,667 bytecodes on `.todo/412`'s machine,
  8,755 -- PAST the cliff, running interpreted unreported -- as the vendored
  slice compiles here. Outlining makes the enclosing method SMALLER than the
  generic emission (one call replaces the whole per-op chain): 8,755 -> 5,339
  bytes with fusion on.
- **Sharing.** Structurally identical sites share one method (`State.byKey`,
  keyed on the tree's op/leaf-kind/constant structure plus the descriptor):
  SHA-256's 64 rounds collapse into a handful of `_fx$N`s.
- **Stack discipline.** Inside the method the operand stack is the tree's own,
  so overflow can bail through an `ArithmeticException` handler (entering a
  handler discards the stack) without interacting with an enclosing
  expression's pending operands -- no spill, no shape juggling at the site.

HotSpot inlines the small `_fx$N`s at hot call sites (a `(+ i 1)` step method
is well under `FreqInlineSize`) and escape analysis then removes even the root
box; the big SHA-round methods stay out of line but are compiled whole, with
register-allocated `long`s inside.

## How exactness survives the raw path

- **Per-leaf guard**: each leaf arrives as an `Object` parameter and unboxes
  once behind `instanceof Long`; anything else (Double, BigInteger, nil)
  branches to the fallback. (On this backend an integer in `long` range is
  always a `Long` -- `BigInteger` only ever holds a magnitude outside it,
  `.kb/core-representation.md` -- so the one test is the whole tier check.)
- **Per-operation overflow check**: `+ - *` go through
  `Math.addExact`/`subtractExact`/`multiplyExact` (HotSpot intrinsics), `mod`
  through `Math.floorMod` (exactly `_mod`'s Long fast path), `rem` through
  `LREM`, `ash` through the emitted `_fxAsh(JJ)J` helper (the count narrowed to
  `int` first, like `_ash`; a wide or overflowing left shift throws). The whole
  fast path sits in an `ArithmeticException`-typed exception region whose
  handler IS the bail -- overflow and zero divisors land in the fallback, which
  recomputes and produces the generic result or the generic error.
- **The fallback recomputes the WHOLE tree from the SAME parameters** through
  the generic helpers the per-op compilers call (`_add`-family, `_logand`,
  `_ash`, `_cmpb`) -- identical results bit for bit, including promotion to
  `BigInteger`; the operations are pure, and the leaves' side effects ran
  exactly once at the call site (they are the call's arguments).
- Two strength reductions ride the fast path, both exact for any `long`:
  `(mod x 2^k)` with a positive power-of-two literal is `x & (2^k - 1)`, and
  `(ash x -k)` with a literal non-positive count is an arithmetic right shift
  clamped at 63.
- The **masked-wrap peephole**: under a non-negative literal `logand` mask or a
  power-of-two `mod`, the whole `+ - *`/left-`ash`-by-literal subtree emits as
  UNCHECKED wrap-around `long` ops (the low `k <= 63` bits of a wrapped result
  equal the infinite-precision ones), so `mod32+`/`rol32`-shaped code pays no
  checks at all.

## The other entry points

- **Fused comparisons** (`= < > <= >=`, binary): an `_fx$N` returning a raw
  `int` truth value -- `LCMP` plus the operator's branch on the fast path, the
  generic `_cmpb`-with-mask on the fallback (so NaN and cross-tier operands
  keep the generic result exactly). Value position boxes the int through the
  shared t/nil emission; **condition position** (`if`/`while` tests, hence
  `when`/`unless`/`dotimes`/`loop` heads) branches on the raw int with `IFEQ`,
  skipping the boxed t/nil round trip per iteration. The both-plain-leaves
  shape (`(< x y)`, nothing fusable) keeps the generic emission unchanged.
- **Substitution**: a call to a fusion-inlinable defun (uniquely defined,
  fixed arity, single closed integer-tree body -- `Ctx.inlinableDefuns`,
  computed in `JvmLispCompiler` before Pass 2; never under `--dynamic`) or a
  `(funcall __FLETn_f ...)` of a let-bound local function
  (`Ctx.localIntLambdas`, registered by `JvmLetCompiler`) substitutes the body
  with parameters bound to the classified arguments, so the tree spans the
  library's own `mod32+`/`rol32`/`sigma0` helpers. A parameter used more than
  once shares its argument's node (leaves evaluate once); a failed substitution
  rolls back the leaves it registered so a side-effecting argument is not
  evaluated twice. An accessor-shaped defun body (exactly `(aref P I)`) maps
  onto an ArefLeaf over the caller's bare-symbol/literal operands.
- **Packed aref leaves** (whenever the program uses arrays at all,
  `Ctx.usesArrays`): a rank-1 `(aref a i)` leaf passes the array as one
  argument and the fast path reads the element raw from EITHER packed
  representation -- the bare `long[]` packed integer vector (elements from slot
  1, past the width header; only emitted under `Ctx.usesIntArray`) and the
  general array's length-6 header over a flat `long[]` (elements from slot 0,
  `.kb/adjustable-arrays.md`). The general shape's discriminator is the same
  one `_arrayp` uses plus the header length: `instanceof ArrayList`,
  `size() != 0`, `get(0) instanceof Object[]`, `length == 6` -- 4 is a
  character vector, 5 a displacement, 3 the boxed general array. The nil
  sentinel (`Long.MIN_VALUE`), an out-of-range index and every non-packed shape
  bail; the fallback calls the same rank-1 helper the ordinary emission would
  (`_ivAref1`/`_fvAref1`/`_arrayAref1` by the same gate), so strings, floats,
  displaced arrays, fill-pointered arrays and every error shape reproduce.
  The INDEX is itself a fusion node: a literal folds into the method, a symbol
  and a `random` draw read the raw slot the prologue filled, and anything else
  (an arithmetic index, a call) is one opaque guarded argument -- so
  `(aref a (random n))` and `(aref a i)` over an unboxed `i` pay no box on the
  way in either. A bailing site is ~1.5 ns/read
  slower than the unfused emission (the guard chain, then the same helper) --
  the price of speculation, paid only by a general array that has been WIDENED
  out of the packed shape, since a packed float array and a string fail the
  first `instanceof` and a fresh `(make-array n)` starts packed.
- **Random leaves**: `(random <integer>)` draws straight into a raw slot --
  the same expression `_random` computes for a `Long` limit,
  `(long) (ThreadLocalRandom.current().nextDouble() * limit)`, so the two are
  ONE formula, not two generators (`.kb/random.md`). A LITERAL limit needs no
  call argument at all, so the `Long.valueOf` the call site used to pay per
  draw disappears with the boxed return.
  **This is the only IMPURE leaf, and its whole protocol follows from that.**
  The fallback re-emits its tree, and a node bound to a substituted parameter
  used twice re-emits TWICE -- so a fallback allowed to draw would draw a
  different number in each occurrence, and `(defun dif (x) (- x x))` over
  `(dif (random lim))` would stop answering 0 (it did, for the length of one
  commit during `.todo/534`). So the draw happens exactly once per leaf, in
  the prologue, on every path, and the fallback only READS it: a `Long` limit
  draws raw and sets the leaf's flag; anything else takes its one draw from
  `_random` into a boxed slot, clears the flag and raises the method's shared
  bail flag, which is tested ONCE after all the draws -- no draw can be jumped
  over, because nothing in the draw phase branches away. Moving the draw inside
  the method reorders it against the other leaves' call-site evaluation, which
  is unobservable: rontolisp has no random-state objects, so nothing can see
  the generator except through the numbers.
- **Unboxed dual-representation locals** (`RawLocal`): an eligible `let`
  binding -- plain lexical, not special, not captured
  (`FreeVarAnalyzer.findCapturedVars`), not a promoted top-level global, not a
  duplicate in its `let`, in a body that defines no nested `defun` (the
  capture analyzer skips `defun` by design) and REASSIGNS the name an
  integer-shaped value (`rawBindingEligible`; an init-only binding boxes once
  either way, and admitting ironclad's functional round-temp chains nearly
  doubled `update-sha512-block`), whose init is not float-contaminated and
  whose assignment values are not either (a Double never fills the raw slot, so
  the representation would be per-site dispatch plus `_ubRead` for nothing --
  `.kb/jvm-double-arithmetic.md`) -- gets a raw `long` slot, a boxed shadow
  slot and an `int` flag slot instead of an ordinary local. The flag is
  non-zero while the raw slot is authoritative; cleared, the shadow is -- 
  INCLUDING when it holds nil (null cannot be the raw marker: a local
  assigned nil must read back as nil -- the bug the wasm version's first cut
  shipped). A flag slot rather than a wasm-style sentinel object, so no
  static field and no `<clinit>` ride along: injected wrapper defuns compile
  with fusion and are then dead-code-shaken, and a field keyed on "some
  wrapper used a raw local" left residue in every artifact. All three slots
  are pre-initialized at the binding (a shadow-only store path must still
  leave the long slot DEFINED, or a later read's `LLOAD` fails verification).
  Assignments funnel through `JvmSetqCompiler` into
  `JvmIntFusionCompiler.compileRawStore`, which dispatches on the RESULT's
  type: a `Long` fills the raw slot and sets the flag (whichever path
  computed it -- a fallback result that normalized back into `long` range is
  a valid raw value), anything else lands boxed in the shadow. Raw-to-raw
  copies transfer all three slots; boxed reads anywhere else go through the
  shared `_ubRead(Object, long, int)` helper. `dotimes`/`loop` counters and
  accumulators are this shape (the expansions are `let` + `while` + `setq`),
  which with fused compares makes a counted integer loop's head and step
  allocation-free after JIT. An assignment in STATEMENT position stores and
  stops there, instead of re-reading the value through `_ubRead` for the caller
  to `pop` (`JvmSetqCompiler.compileForEffect`, reached from
  `JvmExprCompiler.compileForEffect`). Per-name and per-body assignment-site caps
  (`MAX_RAW_ASSIGN_SITES`, `MAX_LET_BODY_ASSIGN_SITES`) keep generated
  straight-line code (fast-http state machines) from paying the per-site
  dispatch bytes thousands of times. A name in `Ctx.rawLocals` is never in
  `Ctx.locals`; `JvmLetCompiler` saves/restores both maps and removes a name
  from either on shadowing, and `JvmDefvarCompiler` checks both.
- **Unboxed promoted GLOBALS** (`Ctx.rawGlobals`, `JvmRawGlobals`): the same
  triple as CLASS FIELDS -- `_gr$X` (`long`), `_gk$X` (`int` flag) beside the
  ordinary `_g$X`, which stays the boxed shadow, so a store that cannot be raw
  is byte-for-byte the `putstatic` the unfused compiler emits and the field a
  program starts with (flag 0, shadow null = nil) is the state a plain global
  starts in. Without it a top-level `(setq s (+ s 1))` boxed the sum into a
  static every iteration -- the one allocation escape analysis cannot remove,
  because the value ESCAPES -- plus a GC write barrier: 16.0 ns an assignment
  against the same accumulator's 3.3 ns as a `let` local. Eligibility is
  program-wide and deliberately narrow, because a global has seams a local does
  not: fusion on and not `--dynamic`, NO eval runtime (the `_genv` mirror holds
  the box, and `eval`/`load`/the FFI seams reach a variable by name), nothing
  concurrent (threads, an http handler, async, sockets -- three fields where
  there was one, and a non-volatile `long` may tear), never DYNAMICALLY bound
  (a `defvar` name is special by CL's rule, but only a `let` that names it makes
  it bindable, and that keeps the save/restore over the single `_g$` field), not
  a compiler-internal cell (`%MV-SPILL`, the stream specials), at least one
  integer-shaped assignment and few enough assignment sites. Everything else
  keeps working untouched because a LEXICAL binding of the name still wins at
  every site (resolved before the global, `JvmIntFusionCompiler.resolveRaw`,
  which is the whole resolution order `compileSymbolRef` uses) and a
  non-integer value still lands in the shadow. Three emissions know the
  representation and every read and write goes through one of them:
  `JvmExprCompiler.compileSpecialRead`, `JvmSetqCompiler` and
  `JvmDefvarCompiler`.

## When fusion does NOT trigger (and must keep not triggering)

- Under `--optimize=size` (`Ctx.intFusion`, the same
  `!prefersSizeOverSpeed()` gate as `Ctx.typedLoops`) and under `--dynamic`.
  `-Drontolisp.debug.nointfusion=true` force-disables at COMPILE time for A/B
  profiling. With fusion off every site falls through to the per-op path
  byte-identically (`theSizeLevelChangesNothingWithoutASpeedForSizeTrade`).
- A single fusable operation with neither a raw-reading leaf
  (RawLeaf/ArefLeaf/RandomLeaf) nor a literal operand -- two plain boxed leaves
  under one op run no leaner fused.
- A node `JvmLispCompiler.hasDoubleLiteral` claims (the recursive
  double-literal routing predicate the per-op compilers read): it keeps the
  unboxed-double path, as a leaf here. An immediate `BigInteger`/ratio literal
  likewise leaves the node to the generic compiler. A tree the classifier DOES
  take, over leaves that turn out to be `Double`s, no longer bails to the
  generic path: the method carries a second, all-Double fast path where the
  `Long` guards jump (`.kb/jvm-double-arithmetic.md`).
- More than 64 ops or 32 leaves (the method carries the tree twice).
- Division (`/`) is never fused (exact ratios); a comparison over two plain
  leaves stays generic.
- A constant-folded root (all-literal tree) declines -- the existing literal
  emission owns it.

## Mechanics

The method PROLOGUE runs in four passes, because a leaf's raw value can be
another leaf's input: the random slots are pre-set, then the draws run (before
any guard, so a bail always finds the value already drawn), then the
`ExprLeaf`/`RawLeaf` guards fill their slots, and last the aref reads, whose
index reads a slot one of the earlier passes filled.

`JvmIntFusionCompiler` (classify -> leaves-as-arguments -> outlined method),
hooked into `JvmExprCompiler`'s arithmetic/bitwise/comparison cases, the
`funcall` case, `compileSymbolRef` (raw-local reads), `JvmSetqCompiler`
(raw-local stores), `JvmIfCompiler`/`JvmWhileCompiler` (raw conditions) and
`JvmLetCompiler` (raw locals, local lambdas). The shared `State` (one per
compile, threaded through every `Ctx`) holds the pending methods, the dedup
map, and the lazily-minted sentinel/`_ubRead`/`_fxAsh` constants; Pass 2d in
`JvmLispCompiler` emits the pending bodies (they compile no Lisp, so the list
cannot grow under the walk), and the class gains the `_ubSentinel` field, its
`<clinit>` line and the helper methods only when something used them -- a
program with no fused site and no raw local is byte-identical to before.
Everything stays verifiable because the emissions go through the ordinary
`Ctx`/`OperandStack`/`StackMapAugmenter` pipeline.

## Numbers (2026-08-26, linux/x86-64, exec jar, temurin 25)

ironclad PBKDF2-HMAC-SHA256, 4096 iterations, 8 derivations per timed batch,
`-o Prog.class` under `java`, steady state (third batch):
`-Drontolisp.debug.nointfusion=true` ~3.75 s -> fused **~0.69 s** per batch --
**5.4x**, ~86 ms per derivation, identical digests. JFR
`ObjectAllocationSample` over a 40-derivation run, weight-summed: total 13.9 GB
-> 4.35 GB (-69%), `java.lang.Long` 12.8 GB -> 3.07 GB (-76%); what remains is
one root box per fused site (the `_fx$N` returns) plus `_ivAref1`/`_ivAset1`
boundary boxes -- the per-INTERMEDIATE allocation is gone.
`update-sha256-block`: 8,755 -> 5,339 bytecodes (compiled by the JIT again).
The `.todo/517` microbenchmarks (top-level spelling, wall incl. startup, best
of three): `loop sum` 0.36 -> **0.19 s** (SBCL 0.21 -- the row's 1.9x gap is
closed), 10^7 `random` 0.45 -> 0.43 s and 10^7 general-array `aref` 1.22 ->
1.23 s (both dominated by the residuals 517 already filed as "measured,
understood, not yet filed": the `_random` helper and the generic `_aref1`
ArrayList read), 10^9 `cdr` unchanged.

### The random and general-aref leaves (2026-08-28, `.todo/534`, same machine)

`.todo/517`'s two rows, 10^7 iterations, compute only (best of 7, JVM startup
0.075 s subtracted). The DEFUN spelling is the one the leaves own; the
top-level spelling of the same program carries a second, larger term the
leaves cannot touch -- see the trigger below.

| row | before | after | Java, primitive | SBCL |
| --- | --- | --- | --- | --- |
| `(+ s (random 10^6))`, defun | 0.087 | **0.072** | 0.050 | -- |
| `(+ s (aref a (random 10^6)))`, defun | 0.201 | **0.178** | 0.094 | -- |
| the same, top-level spelling | 0.391 | 0.351 | -- | 0.261 |
| `(+ s (random 10^6))`, top-level | 0.214 | 0.204 | -- | 0.148 |

The hot loop of both spikes' `_top$0` now holds NO `Long.valueOf` and no
`_add`: the limit is baked into the fused method, the draw and the element
read stay raw, and the whole tree is one `invokestatic`. On the defun spelling
that is 1.4x and 1.9x of hand-written primitive Java, both inside 2x.
Isolated on a 1,000-element array (cache-resident, so the row measures the
read and not the memory system): the packed hit is 0.088, the unfused
emission 0.108, and a WIDENED general array -- which runs the guard chain and
then bails into the same `_aref1` -- 0.123. The speculation wins 2 ns/read
where it hits and loses 1.5 ns where it misses.

### The promoted global's triple (2026-08-28, `.todo/556`, same machine)

10^7 iterations of `(setq s (+ s 1))`, compute only (best of 5, JVM startup
0.075 s subtracted), the accumulator spelled two ways:

| accumulator | before | after |
| --- | --- | --- |
| `(let ((s 0)) ...)` -- `RawLocal` | 0.033 | 0.033 |
| `(defparameter s 0)` -- static field | 0.160 | **0.036** |

The two spellings now cost the same per assignment, which is the whole point.
`.todo/517`'s two top-level rows follow it down (compute only): 10^7 x `random`
0.204 -> **0.075** (the defun spelling is 0.072, hand-written primitive Java
0.050), 10^7 x `aref` 0.351 -> **0.205** (defun 0.178, Java 0.094). The
top-level spelling is no longer a different program from the defun spelling.
The root box `_fx$N` still returns is unboxed at the store site and dies there,
so escape analysis removes it -- re-evaluation trigger 1 below is what would
remove the box itself.

**The other integer-valued built-ins do NOT earn a leaf**, measured the same
day rather than assumed: `char-code` over `(char s i)` costs 1.3 ns/iteration
more than the same loop without it (HotSpot inlines `_charCode` and escape
analysis removes both the `int[1]` character and the box, so a leaf would save
under a nanosecond); `elt` on a packed vector is 0.7 ns/read behind the fused
`aref` (the whole gap a leaf could close), and it is not the spelling a hot
vector loop uses; `length` has no cheap tag test at all -- its answer IS a
three-way dispatch over string/vector/list, so a leaf would reproduce
`_length` and save only the return box.

## Pinning tests

`JvmLispCompilerTest.fusedIntegerExpressionTreesMatchTheGenericPath` (overflow
promotion, float/nil bails, mod/rem/ash sign semantics, the strength
reductions, side-effects-once under substitution, the raw-local nil re-read,
packed-aref raw reads and their error shapes, the general array's packed
shape with a literal / symbol / `random` index, its nil-element, widened,
displaced, string and float-index bails, a `random` leaf with a literal and a
variable limit, a float limit's bail into `_random`, a `random` leaf under an
overflow promotion, and a `random` leaf substituted into a defun body that
uses its parameter TWICE -- which must still answer `(- x x)` = 0, the
once-only-draw pin -- NaN comparisons, zero divisors; and that `SIZE`
compiles the same program to different bytes),
`fusedArefLeavesReadTheGeneralArraysPackedShapeAndBailForEveryOther` (the same
general-array leaf in a program holding NO packed integer vector, so only the
ArrayList dispatch is emitted, plus the fill-pointered and adjustable bails),
`unboxedTopLevelGlobalsAnswerWhatTheBoxedStaticFieldAnswers` (a promoted global's
every tier -- float, string, nil, a bignum promotion out of `long` range -- read
back through the shadow, a lexical binding of the name still winning over the
global, and one global declining in the same program another takes),
`aDynamicallyBoundSpecialAndAnEvaldGlobalDeclineTheUnboxedRepresentation` (the
two seams a local does not have, pinned by the absence of the `_gr$` field as
well as by the answers),
`theSizeLevelChangesNothingWithoutASpeedForSizeTrade` (a program with neither a
typed loop nor a fused site stays byte-identical across levels),
`JvmLibraryMethodSizeTest` (no emitted defun/lambda method of the
ironclad-loading program crosses 8000 bytecodes -- LIBRARY code, the guard
`.todo/412` demanded), and the existing `fused-integer-expression-trees` /
`flet-fusion-and-unboxed-locals` / `fused-comparisons-and-raw-leaf-stores`
`fused-random-and-aref-leaves` ci-spec cases, which pin all four backends'
outputs against each other.

## Re-evaluation triggers

- The store dispatch re-boxes a raw local's fast-path value at every
  assignment (`Long.valueOf` inside `_fx$N`, unboxed again at the site). EA
  removes it where the method inlines; if a profile ever shows allocation on a
  hot NON-inlined store, a raw-returning variant (`(...)J` + a separate bail
  protocol) is the next step.
- Params are not eligible for the dual representation (they arrive boxed by
  signature), so a defun-body accumulator that is a parameter stays boxed --
  same trigger as the wasm file's.
- `%aset` values fuse boxed (the value tree collapses but `_ivAset1` still
  takes a boxed operand); a raw store path would shave one box per store.
- A global that the eligibility gate refuses WHOLESALE -- any program that
  touches `eval`, threads, an http handler, async or sockets keeps the boxed
  static field for every one of its globals. The concurrency half is the
  conservative reading (a torn non-volatile `long`), not a measured problem; a
  `volatile` triple, or a per-name "assigned only from the main thread" proof,
  would narrow it if a real program ever wants the fast path there.
