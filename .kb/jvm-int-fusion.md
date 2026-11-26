# JVM integer expression-tree fusion (outlined `_fx$N` methods)

**Invariant: fusing an integer expression tree must never change a result, an observable side
effect, or an error shape -- the fast path is an optimization with a total fallback.**

JVM analogue of `.kb/wasm-int-fusion.md`, integer sibling of `.kb/jvm-typed-loops.md`. A nested
tree over `+ - * mod rem logand logior logxor lognot ash` (`1+`/`1-` normalized) becomes ONE
unboxed evaluation: leaves evaluated once, left to right; interior raw `long`; only the root
boxes. It removes an allocation rate, not arithmetic.

## Outlined, not inline
A fused site is its own private static `_fx$N` called by one `invokestatic`. The tree is emitted
TWICE (fast + fallback), so inlining would cross the 8000-byte `HugeMethodLimit`
(`.kb/hot-path-method-size.md`). Structurally identical sites share one method (`State.byKey`),
unless an operation reports a source site other than the call's (multi-line tree, inlined defun
body): then the key carries the sites and the fallback marks each operation's (and a `random`
draw's), so the uncaught report names the right line ([error-handling.md](error-handling.md)).
Inside the method the operand stack is the tree's own, so the `ArithmeticException` bail (which
discards the stack) cannot disturb an enclosing expression's operands. A tree over exact locals
that cannot throw is emitted in place ("Exact locals").

## How exactness survives the raw path
- **Per-leaf guard**: `instanceof Long` is the whole tier check (an integer in `long` range is
  always a `Long`, `.kb/core-representation.md`). A declared integer leaf is cast instead; a method
  with nothing else to bail on has no fallback and no handler
  ([declarations-type-checks.md](declarations-type-checks.md), "Declared integer ranges", "On
  the JVM").
- **Per-operation overflow check**: `Math.*Exact`, `Math.floorMod` (`mod`), `LREM` (`rem`),
  `_fxAsh(JJ)J` (`ash`); the fast path sits in an `ArithmeticException` region whose handler IS
  the bail.
- **The fallback recomputes the WHOLE tree from the SAME parameters** through the generic helpers
  -- identical bit for bit incl. `BigInteger` promotion; leaf side effects ran once at the call.
- Exact strength reductions: `(mod x 2^k)` -> mask; `(ash x -k)` -> arithmetic shift clamped at 63.
- **Masked-wrap peephole**: under a non-negative literal `logand` mask or a power-of-two `mod`,
  `+ - *`/left-`ash`-by-literal emit as UNCHECKED wrap-around ops.
- **Masked signed field**: `(%mask-signed-field k x)` (SBCL's `sb-c::mask-signed-field`) with
  literal `1 <= k <= 64` emits `x` wrapped and sign-extends a narrower field, so
  `(%mask-signed-field 64 (* a b))` is one `LMUL` (the Clojure unchecked verbs,
  `.kb/clojure-frontend.md`). The fallback is the lowering's arm
  (`LispMacroExpander.expandMaskSignedField`), so a non-integer reports as `LOGXOR` exactly like
  the unfused lowering and the interpreter. Fused over a lone leaf too: the lowering is inline at
  every site, the fused method shared by shape; every Java front-end integral wrap is such a field
  ([java-frontend.md](java-frontend.md), "Numbers"): `Zone.class` 282,879 B declining vs 271,945
  fused. Pinned by
  `JvmLispCompilerTest.aMaskedSignedFieldOverAProductMultipliesInOneUncheckedLong`
  (`testsupport/MaskSignedFieldProgram`, default and size levels print SBCL's values).
- **Unsigned shift**: `(%mask-signed-field 64 (ash (logand x 2^64-1) (- (logand c k))))`, a Java
  `long >>>` by a computed count (`LispMacroExpander.unsignedShift`, the `--no-gc` backend's
  matcher too), is the `%ushr` node, one `LUSHR`. Otherwise the 2^64 - 1 literal made
  `(logand x 2^64-1)` a leaf failing its guard for every negative `x`. Its operand is the node
  `(logand x -1)`: the masking `logand`'s application in the interpreter's order, so a
  non-integer `x` signals before the count's leaves run (probe). Measured 2026-11-26 (a SHA-512
  digest reused for 20,000 messages, median of rounds 6-11, three alternated pairs at load 26-33):
  63-byte messages Graal 255-314 -> 54-90 ms, C2 337-352 -> 99-113; 64-byte 326-374 -> 130-175,
  C2 444-445 -> 195-323, the rest the sentinel's bails ("Declared arrays"). The `java-spec.yaml`
  corpus 8,222,992 -> 8,222,954 B, the reused-digest program 76,580 -> 76,421. Pinned by the
  same test (`msf-ushr`, `msf-ushr-order`: an `LUSHR` in a fused method).

## The interpreter's order
The call evaluates every leaf before the method applies anything, which matches the interpreter
only where nothing between an application and a later leaf can tell
(`.kb/argument-evaluation-order.md`, "An operation applies after its operands"); e.g.
`(+ (* 2 a) (progn (princ "x") 1))` must not print before `*` signals.

- **Events**: classification records each leaf evaluation and application (operation, aref
  read, draw) in the interpreter's order (`Site.events`).
- **Boundaries** (`planOrder`), before an observable leaf while applications are pending:
  `PendingGuard` (pending operations' leaves are `Long`s, divisors non-zero, counts within `int`
  -> cannot signal), `LeafGuard` (the leaf is integer arithmetic over quiet `Long` variables ->
  applications stay pending), anything else (aref read before a possibly storing leaf, a draw, a
  computed divisor/count) made opaque (`classifyOrdered` repeats until stable;
  `Ordering.expansions` keeps an `ldb` expansion's conses stable; a site whose root is asked
  declines).
- **A failed check calls the probe** `_fxp$N` (void, shared by structure): the pending
  applications through the generic helpers, values dropped; one that signals signals there.
- With a boundary, leaves go into temporaries in order; more than `MAX_ORDER_CHECKS` leaf tests
  declines fusion.

Why: the checks are the fused method's own guards, so the JIT shares them once inlined, and the
generic helpers run only on failure. Rejected: cutting the tree at each observable leaf (a box per
cut), a probe recomputing the pending subtree raw (duplicate hot-path arithmetic), making every
pending aref read opaque (boxed reads in UTF-8/MD5 loops), declining such trees. Census over 248
programs: 159,287 fused sites, 4,737 with an application pending before an observable leaf.

## Entry points beyond plain trees
- **Fused comparisons** (`= < > <= >=`, binary): `_fx$N` returning raw `int`; in condition
  position (`if`/`while` and what expands to them) branches with `IFEQ`. Two plain leaves stay
  generic.
- **Substitution** of a fusion-inlinable defun (`Ctx.inlinableDefuns`, never under `--dynamic`)
  or a let-bound local function (`Ctx.localIntLambdas`), so a tree spans `mod32+`/`rol32`/`sigma0`.
  A parameter used twice SHARES its argument's node; a failed substitution rolls back its leaves;
  a body that is exactly `(aref P I)` maps onto an ArefLeaf. A defun tail settled for the
  multiple-value channel is read through its clear, and a root-position call
  (`tryCompileDefunCall`) clears after its tree -- the wasm twin's rule
  ([wasm-int-fusion.md](wasm-int-fusion.md), "A settled defun tail"); pinned by
  `JvmLispCompilerTest.fusedIntegerExpressionTreesMatchTheGenericPath` (`MIX32` calls neither
  `MOD32+` nor `ROL32`). ironclad PBKDF2 x4096 warm: 350-500 ms declined, 136 ms substituted; trap:
  the FIRST run is slower under Graal (1.4 -> 1.7 s, 389 `_fx$` methods to tier up).
- **A name with a cell** (`CelledCall`: `notinline`, every defun of a program holding `eval`, a
  linked class's declared names) substitutes too, the wasm twin's design
  ([wasm-int-fusion.md](wasm-int-fusion.md), "A name with a cell"): the cell check
  (`emitCellChecks`) runs after the draws, before the guards; the fallback calls `_n$name` once
  however many places read it. The key numbers each call, so two equal calls do not share a
  method. Order: `keepsCelledOrder` (a raw global is no inert leaf: a replacement can write it).
  A root call that would take the tail entry `_nt$` (`JvmTailBounce.takesTailEntry`) stays a call;
  a root call's fused method clears the multiple-value channel on its fast path only
  (`Pending.clearsChannel`). Pinned by the `eval` leg of
  `fusedIntegerExpressionTreesMatchTheGenericPath` and ci-spec
  `a-defun-written-through-a-computed-name-answers-the-new-definition` (`cwn-add32`).
- **Packed aref leaves** (whenever `Ctx.usesArrays`): a rank-1 `(aref a i)` reads raw from the
  bare `long[]` packed integer vector (elements from slot 1; only under `Ctx.usesIntArray`) or the
  general array's length-6 header over a flat `long[]` (elements from slot 0,
  `.kb/adjustable-arrays.md`). Discriminator: `instanceof ArrayList`, `size() != 0`,
  `get(0) instanceof Object[]`, `length == 6` -- **4 is a character vector, 5 a displacement, 3
  the boxed general array** -- and dims of length 1 (the packed shape holds every rank). The nil
  sentinel (`Long.MIN_VALUE`), an out-of-range index (incl. a long past int range: no truncation
  may turn it into a read), another rank and every other shape bail into the fallback: the
  ordinary rank-1 aref with its rank and subscript checks (`JvmArrayCompiler.emitArefArrayCheck`),
  not the read helper alone (which reads rank-2 storage as rank 1). The rank guard is one
  `arraylength` on the header already loaded; a rank-1-only header tag was rejected (it would move
  every consumer of the tag, [adjustable-arrays.md](adjustable-arrays.md)).
  **A typed read `(the <integer range> (aref a i))` is this leaf only of an array a
  declaration pins** ("Declared arrays"): with all three arms SHA-512 on C2 went 398-639 ->
  203-243 ms but Graal's steady state 113-125 -> 152-177 (the fused method 592 -> 1,323 B, no
  longer inlined), classes +0.3-5%; an unpinned one stays a trusted opaque leaf.
  **The INDEX is an operand of the tree** (`arefLeaf`): its operations apply before the read, so
  `(aref v (+ i 1))` puts no check between reads; the prologue computes it raw inside the checked
  region. JVM steady state unchanged (C2 already scalar-replaced the index box), first round
  faster; the WASM half gains.
- **Random leaves**: `(random <integer>)` draws with `_random`'s formula for a `Long` limit
  (`.kb/random.md`). **The only IMPURE leaf**: the fallback re-emits its tree and a shared
  parameter node emits twice, so a drawing fallback would make `(dif (random lim))` over
  `(defun dif (x) (- x x))` stop answering 0. The draw happens exactly once per leaf, in the
  prologue, on every path; the fallback only READS it. A non-`Long` limit draws through `_random`
  into a boxed slot and raises the shared bail flag, tested ONCE after all draws.
- **Unboxed dual-representation locals** (`RawLocal`): raw `long` slot + boxed shadow + `int`
  flag. Eligible: plain lexical, not special, not captured (`FreeVarAnalyzer.findCapturedVars`,
  asked BEFORE `rawBindingEligible`), not a promoted global, not duplicated in its `let`, no nested
  `defun` in the body, REASSIGNS an integer-shaped value, nothing float-contaminated
  (`.kb/jvm-double-arithmetic.md`). Traps: **null cannot be the raw marker** (a local assigned nil
  reads back nil), hence a flag, which also keeps a static field and `<clinit>` out; **all three
  slots are pre-initialized at the binding**, or a later `LLOAD` fails verification. Stores go
  through `JvmIntFusionCompiler.compileRawStore` (dispatching on the RESULT type), other reads
  through `_ubRead(Object, long, int)`; `MAX_RAW_ASSIGN_SITES` / `MAX_LET_BODY_ASSIGN_SITES` cap
  the bytes. A name in `Ctx.rawLocals` is never in `Ctx.locals`. A declared binding read mostly raw
  takes the exact flavour ("Exact locals").
- **Unboxed promoted GLOBALS** (`Ctx.rawGlobals`, `JvmRawGlobals`): the same triple as class
  fields `_gr$X`, `_gk$X` beside the boxed `_g$X`, so a non-raw store is byte-for-byte the unfused
  `putstatic`. Eligibility is program-wide: fusion on, not `--dynamic`, NO eval runtime (`_genv`
  holds the box), nothing concurrent (a non-volatile `long` may tear), never dynamically bound, not
  a compiler-internal cell (`%MV-SPILL`, stream specials), at least one integer assignment, few
  enough sites. A lexical binding wins at every site (`JvmIntFusionCompiler.resolveRaw`).
  **`JvmRawGlobals.collect` counts every candidate's sites in ONE walk**: one
  walk per global made the `java-spec.yaml` corpus's JVM compile 339 s (one walk: 46-64 s).
  `JvmRawGlobalsTest` (a hang the timeout cuts).
- **The counted-loop STEP is emitted INLINE, ahead of the outlined method**
  (`emitRawStepFastPath`), guarded by the flag and `Math.addExact`'s overflow condition; two
  declines branch into the outlined call (stale raw slot, overflowing step). Why: C2
  scalar-replaces a dead box only once it compiles the loop, and a 1000-iteration loop never
  reaches OSR. Pinned by
  `JvmLispCompilerTest.aCountedLoopStepPromotesAtTheFixnumBoundaryAndKeepsSteppingOnABignum`.

## Exact locals (2026-11-25, `.todo/l73`)
**A `let` binding a bound declaration gives an integer range within `long`, read mostly raw,
holds a `Long` at every point: one raw `long` slot (`RawLocal.exact`), no shadow, no flag** --
wasm's declared exact flavour ([wasm-unboxed-locals.md](wasm-unboxed-locals.md)). What a Java
`int` or `long` local read in arithmetic is ([java-frontend.md](java-frontend.md), "Numbers").

- **Why.** A declared local bound once was an `Object` slot, every statement a fused method
  answering a fresh `Long` (the dual flavour needs a reassignment), and a reassigned one's store
  unboxed that box again. In `Sha3.permute` (`Sha3.java`) every statement of a round allocated and
  C2 inlined none of the round's methods (`size > DesiredMethodLimit` in the 4,958 B caller);
  hoisting the 59 temporaries to reassign them changed nothing.
- **Eligible** (`JvmLetCompiler`, ahead of the dual check): the dual conditions but the
  reassignment, the declaration in the `let`'s own head (`DeclaredIntegerTypes.declared`), and
  read mostly raw (`DeclaredIntegerTypes.readMostlyRaw`, wasm's rule, the init counted as an
  assignment): a boxed read is `Long.valueOf` of the slot, which allocates past its cache where an
  `Object` slot holds the box its store made. The rule counts a `let`'s inits (counting none left
  `a10` of `permute`, read in `c1`'s and `e02`'s inits, dual).
- **Stores** (`compileRawStore`): a fused tree's method answers `J` (`Pending.rawReturn`, a
  declared dual local's too): no box on the fast path, no double path, the fallback's value
  unboxed by `checkcast Long` -- a bignum, a float or nil there is the false declaration, a
  catchable `type-error` ([declarations-type-checks.md](declarations-type-checks.md), "On the
  JVM"). Any other value unboxes so in the caller; an exact source is copied `lload`/`lstore`.
- **Reads**: a fused tree's `RawLeaf` is one `long` argument (trusted: no guard), anything else
  `Long.valueOf`, as is the crossing into a `JvmBodyOutliner` continuation, where the name is an
  ordinary local.
- **In place** (`computedInPlace`): a tree whose leaves are all exact locals and whose fast path
  cannot throw (`fastThrows`: wrapped `+ - *`, bitwise operations, literal shifts and masks) is
  that fast path where it stands -- stored raw, boxed in value position, a 0/1 `int` for a
  comparison -- with no `_fx$` method: the method would have nothing to bail on, and C2 did not
  inline it in a large caller (with the slots alone `permute` was 3,309 B, and C2 spent its budget
  on the 25 cold lane reads first). Javac's code for the same statement.
- **Measured** (Xeon E5-2697A v4, Oracle GraalVM 25.0.5.1; SHA3-256 of 4 MiB in 64 KiB pieces,
  steady runs): base 370-733 ms Graal / 786-1,467 C2; outlined `J` methods alone 78-114 / 229-391;
  in place 62-112 / 85-203 (the JDK 14-15); first run 213-408 vs 1,168-1,406. SHA-512 C2
  697-1,585 -> 398-1,006, Graal the same; MD5, SHA-1, SHA-256, MD2 unchanged. Their arrays:
  "Declared arrays". Classes shrink (l18's `Computed` 261,454 ->
  203,936 B, the `java-spec.yaml` corpus 8,211,494 -> 8,113,471); ironclad's largest method
  unchanged (6,192). A program declaring no integer is byte-identical.

## Declared arrays (2026-11-26)
**A rank-1 read or store of an array a declaration pins (`DeclaredArrayTypes.Pin`) takes that
representation's one arm in the fused method: an `(unsigned-byte 8)` vector's `byte[]`, an
`(unsigned-byte 16|32)` vector's `long[]`, a general array's packed `long[]` (`_fxLongs`,
inline) and its other shapes (`_fxElt`, `_fxPut`, out of line); every other value bails to the
generic access.** What the Java front end declares of every array variable and asserts of every
other array form ([java-frontend.md](java-frontend.md), "Numbers").

- **The pin** (`arrayPinOfExpr`): a variable whose declaration is its binding's own
  (`Ctx.declaredArrays`, `declaredLong`'s rule) or a `(the spec form)`. No packed float kind,
  and no packed integer kind without `Ctx.usesIntArray`.
- **Reads** (`emitPinnedRead`): the arm alone; nil, an index out of range and the sentinel bail
  as unpinned. A pinned `(the <integer range> (aref a i))` is the leaf, so the order plan no
  longer makes the tree's other reads opaque.
- **Stores** (`tryCompileStore`): a rank-1 `%aset` of a pinned array is a fused method rooted at
  the store -- index and value raw, the arm's checks, one write, `V` in statement position
  (`JvmExprCompiler.compileStatementStore`); the fallback is the generic store over the same
  arguments, in its order. An index that may be no integer (an untrusted leaf, an aref read, a
  draw, a celled call) beside a value leaf that can be observed is checked after the value
  (`ArefLeaf.checkWaits`, part of the method's key), as the generic store checks it. Where threads
  run, `_fxPut` stores the box too.
- **No string arm**: `(setf (aref var i) v)` of a pinned variable is its `%aset` at every level
  (`JvmArrayCompiler.nonStringArefStore`, wasm's twin); a string there is the false declaration's
  catchable error.
- **The packed arm stays inline**: a shared helper's profile is every site's -- `_fxElt` reading
  every shape kept the boxed path in each inlined copy once one array was boxed (Graal's SHA-512
  95 -> 134 ms).
- **Measured** (Xeon E5-2697A v4, Oracle GraalVM 25.0.5.1, SHA-512 of 4 MiB in 64 KiB pieces, a
  fresh digest each round, median of rounds 6-11, five alternated pairs at load 45-60): Graal
  120-132 -> 91-106 ms, C2 335-504 -> 138-161; the first round 921-1,099 -> 178-231 (C2 774-1,045
  -> 189-252). After MD5, SHA-1, SHA-256 and SHA3-256 in one JVM: SHA-512 Graal 222-532 -> 95-193,
  C2 424-653 -> 223-241; SHA3-256 Graal 102-208 -> 40-73, C2 172-202 -> 83-124; the others within
  noise. Classes shrink: the `java-spec.yaml` corpus 8,258,946 -> 8,230,665 B, the SHA-512
  program 310,720 -> 297,337.
- **A read of the sentinel bails its whole tree** (`Long.MIN_VALUE` held packed,
  [adjustable-arrays.md](adjustable-arrays.md)), whose fallback wraps each `long` through the
  2^64 - 1 bignum: SHA-512's padding word of a message a multiple of 8 bytes long. A digest reused
  for 64-byte messages: ~45% of JFR samples in `BigInteger` under `Sha512.block`.
- **What C2 leaves**: it inlines no method past 325 bytecodes, which the round's fused method
  (877 B, most of it the fallback) is.

## When fusion does NOT trigger (and must keep not triggering)
- `--optimize=size` (`Ctx.intFusion`, the `!prefersSizeOverSpeed()` gate) and `--dynamic`;
  `-Drontolisp.debug.nointfusion=true` force-disables at COMPILE time. Off, every site falls
  through byte-identically.
- A single fusable op with neither a raw-reading leaf nor a literal operand; more than 64 ops or
  32 leaves; division (`/`, exact ratios); a comparison over two plain leaves; a constant-folded
  root.
- A node `JvmLispCompiler.hasDoubleLiteral` claims, and an immediate `BigInteger`/ratio literal. A
  taken tree whose leaves turn out to be `Double`s does NOT bail: the method carries a second
  all-Double fast path (`.kb/jvm-double-arithmetic.md`).
- **Other integer-valued built-ins do not earn a leaf** (measured): `char-code`, `elt` on a packed
  vector, `length` (whose answer IS a three-way dispatch).
- **`floor`/`truncate` of two integers are not ops** (measured): a leaf calling `_iquo` already
  runs as fast as a fused `mod` (`.kb/linalg-simd.md`, "Two integers").

## Mechanics
The prologue runs in FOUR passes, because a leaf's raw value can be another leaf's input: random
slots pre-set, then the draws (before any guard, so a bail always finds the value drawn), then the
`ExprLeaf`/`RawLeaf` guards, last the aref reads. Pass 2d emits the pending bodies (they compile no
Lisp, so the list cannot grow under the walk). A program with no fused site and no raw local is
byte-identical.

## Not this mechanism's fault
A captured `let` variable assigned INLINE in a sibling branch failing with
`class java.lang.Long cannot be cast to class [Ljava/lang/Object;` is the ONE-BYTE local index
limit (`.kb/jvm-method-size-limits.md`): past 255 slots `astore 256` truncates to `astore 0`. It
reproduces with fusion off.

## Pinning tests
`JvmLispCompilerTest.fusedIntegerExpressionTreesMatchTheGenericPath`,
`.aDeclaredIntegerLeafIsCastAndAMethodWithNothingElseToBailOnHasNoFallback` (exact locals too:
`DeclaredIntegerProgram`'s `rho` calls no `_fx$` method and no `_ubRead`, `lanes`' first fused
method answers `J`; declared arrays: `sched` calls its stores' `V` methods and one comparison,
nothing else; the program on both wasm backends in
`WasmLispCompilerIntegrationTest.fusedLocalFunctionsAndUnboxedLocalsMatchTheGenericPath`),
`.fusedArefLeavesReadTheGeneralArraysPackedShapeAndBailForEveryOther`,
`.anArithmeticArefIndexIsComputedInsideTheFusedTree` (one `_fx$` call and no probe per site),
`.compileAndRunArefChecksItsArraysRankAndItsSubscriptsType` (`ArefRankAndSubscriptChecksFixture`,
both levels),
`.unboxedTopLevelGlobalsAnswerWhatTheBoxedStaticFieldAnswers`,
`.aDynamicallyBoundSpecialAndAnEvaldGlobalDeclineTheUnboxedRepresentation` (pinned by the ABSENCE
of the `_gr$` field too), `.theSizeLevelChangesNothingWithoutASpeedForSizeTrade`;
`JvmLibraryMethodSizeTest`; ci-spec `fused-integer-expression-trees`,
`flet-fusion-and-unboxed-locals`, `fused-comparisons-and-raw-leaf-stores`,
`fused-random-and-aref-leaves`. The order: `FastPathEvaluationOrderFixture`
(`.compileAndRunFastPathsKeepTheInterpretersEvaluationOrder`, both levels; its `eo-dstore` and
`eo-dread` rows a declared array's) and ci-spec `fast-paths-keep-the-evaluation-order`; a
declared array's reports: `ArefRankAndSubscriptChecksFixture.ORDER_SOURCE`'s last rows.

## Unfinished
- An UNDECLARED dual local's store re-boxes its fast-path value at every assignment (a declared
  one's method answers `J`, "Exact locals"); its `(...)J` variant needs a bail protocol for a
  value that is no `Long`.
- Params are not eligible for the dual representation (they arrive boxed by signature), nor for
  the exact one: a declared parameter allocates nothing (its raw reads unbox), and the unboxing at
  the entry wasm does (`unboxDeclaredParameters`) was not measured here.
- A program touching `eval`, threads, an http handler, async or sockets keeps the boxed static
  field for EVERY global; the concurrency half is a conservative reading, not a measured problem.
