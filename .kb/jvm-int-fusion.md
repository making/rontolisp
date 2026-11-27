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
A fallback past 35 bytecodes is the method `_fxb$N` ("The fallback is a method of its own").
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
  the bail -- no region and no handler where none of its operations throws
  (`throwsArithmetic`).
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
- **The sentinel integer: the twin `_fxs$N` (2026-11-27).** `Long.MIN_VALUE` held packed
  ([adjustable-arrays.md](adjustable-arrays.md)) is SHA-512's padding word of a message a
  multiple of 8 bytes long; a read of it bailed the whole tree into the fallback, which wraps
  each `long` through the 2^64 - 1 bignum (64-byte messages: ~45% of JFR samples in `BigInteger`,
  and the store of the padding word bailed too). **Invariant: the fused method is unchanged --
  its sentinel read and store bail -- and where a pin declares a general array whose least
  element is `Long.MIN_VALUE` (`holdsSentinelInteger`: `(signed-byte 64)`, the Java front end's
  `long[]`), its bail calls the twin `_fxs$N` (`answersSentinel`): the same fast path, no double
  path, whose read asks the list's own slot inline (a `Long` equal to the integer, else bail:
  nil, no slot, a racing box, `_fxElt`'s answer) and whose store of the integer is `_rmSet`'s,
  ending the method; its bail runs the fallback.** No twin for a tree with a draw (the twin
  would draw again), an unpinned read, a `simple-vector` or a narrower element type, which bail
  as before: a program declaring no such array is byte-identical (the ten `bench-report`
  programs). The method key carries `+` for such a pin.
  - Why a twin, measured (Xeon E5-2697A v4, Oracle GraalVM 25.0.5.1, a digest reused for 20,000
    messages / a fresh one over 4 MiB in 64 KiB pieces, median of rounds 6-11 of a launch, ms).
    The question in the fused method itself, inline (56 bytes a read): Graal 64-byte 136-205 ->
    50-60, 63-byte 69-70 -> 52-56, 4 MiB 82-84 -> 56-70, but C2 4 MiB 74-94 -> 99-117: the
    round went 298 -> 358 bytecodes, past `FreqInlineSize` (325, "hot method too big"). As a
    helper's call (11 bytes): Graal 63-byte 68-69 -> 74-77 though never taken, 4 MiB no gain.
    The twin: Graal 64-byte 106-170 -> 59-61, 63-byte 60-62 -> 57-68, 4 MiB 72-85 -> 72-79; C2
    64-byte 203-282 -> 83-95, 63-byte 82-92 -> 82-95, 4 MiB 73-111 -> 81-115; SHA3-256 4 MiB
    within noise on both. 64-byte messages now cost what 63-byte ones do.
  - Cost: classes. SHA-512's program 77,231 -> 83,909 B (23 twins, 4,770 bytecodes), the five
    digests' 192,089 -> 206,313.
- **What C2 leaves**: "The fallback is a method of its own", "What C2 still leaves".

## The fallback is a method of its own (2026-11-26)
**A fallback past 35 bytecodes (`FALLBACK_INLINE_LIMIT`) is `_fxb$N`, which the bail calls with
the fused method's own arguments; a fast path none of whose operations throws
(`throwsArithmetic`: the tree's, a computed subscript's, a stored value's) has no handler.**

- **Why: one bail slowed a fused method for the life of the process.** C2 recompiles a method
  whose bail it has seen taken with the fallback's helper calls inlined. SHA-512's round
  (888 bytecodes, 607 of them the fallback) was 2,888 B of machine code before its first bail
  and 13,072 after, the schedule's store 4,520 -> 8,112, the block's load 5,240 -> 17,592; with
  the fallback a call 2,992 / 4,272 / 5,224 (`-XX:+LogCompilation`, an `nmethod`'s
  `stub_offset - insts_offset`). Every message whose length is a multiple of 8 bytes bails
  there: its padding word is `Long.MIN_VALUE`, the packed shape's nil. 4 MiB plus one byte,
  which never bails, ran at 102-104 ms in either layout; 4 MiB at 137-144 in the old one.
- **The limit is C2's `MaxInlineSize` (35)**: a callee that small it inlines wherever a call has
  run, so outlining one moves nothing. Measured (C2, five digests in one JVM, ms): limit 100
  MD5 45-47, SHA-256 117-120; limit 35 42-45, 106-111; every fallback outlined 46-61, 106-115,
  with the five digests' class 159,842 / 161,578 / 166,845 B (160,158 before).
- **The fallback is emitted first, as the method** (`emitMethods`): its size decides. A small
  one is emitted again in place, `forgetFallback` clearing what the first emission left on the
  tree's celled calls.
- **A draw is the prologue's**: `_fxb$N` takes each `RandomLeaf`'s slots as arguments after the
  method's own. A dual local's raw half may hold its shadow's value by the bail; the fallback
  reads the half the flag names, as it did in place.
- **The all-Double path stays in the fused method**: it is a program of doubles' fast path, and
  as `_fxb$N`'s head it would be a call per site wherever the two together pass 325 bytecodes.
- **No handler**: the exception table entry and its frame were 1.8% of the five digests' class
  (160,158 -> 157,219 B with the handlers alone gone), and SHA-512 after the four others ran at
  106-116 ms against 143-149 with nothing else changed.
- **Measured** (Xeon E5-2697A v4, Oracle GraalVM 25.0.5.1, C2 by `-XX:-UseJVMCICompiler`; the
  box at load 30-60, so each figure is the MINIMUM of one launch's steady rounds and a range is
  over launches; both arms built from one tree and alternated; a fresh digest each round, 4 MiB
  in 64 KiB pieces). SHA-512 alone (rounds 6-29, five launches): C2 137-138 -> 93-110 ms, Graal
  90-93 -> 83-85. After MD5, SHA-1, SHA-256 and SHA3-256 in one JVM (rounds 6-15): C2 MD5 55-74
  -> 42-45, SHA-1 108-166 -> 88-91, SHA-256 132-148 -> 106-111, SHA-512 143-149 -> 99-108;
  Graal MD5 32-33, SHA-1 54-56, SHA-256 76-79 either way, SHA-512 90-91 -> 82; SHA3-256
  (computed in place) within noise on both. Classes: SHA-512's program 147,811 -> 147,754 B,
  the five digests' 160,158 -> 161,578 (compiled in 5.6-6.7 s either way), the ten
  `bench-report` programs -62 .. +31 B. A program with no fused site is byte-identical.
- **What C2 still leaves** (SHA-512 alone, 83 ms with everything inlined: `-XX:InlineSmallCode=6000`
  on this layout, `-XX:FreqInlineSize=1000 -XX:InlineSmallCode=30000` on the old one). C2 does
  not inline a callee it has already compiled to more than `InlineSmallCode` (2,500 B of
  machine code with C2 the top tier) -- "already compiled into a big method" -- and a fused
  method is called far more often than its caller, so it is compiled first: the round, now 298
  bytecodes and under `FreqInlineSize` (325), is 2,840-2,992 B and is inlined only when the
  block's compile wins the race (93-95 ms; 107-110 when it loses). A general array's read is
  928-944 B of it by itself (`_fxLongs`, 88 bytecodes inlined, 608 B), the schedule's store
  with three of them 4,272-4,384; the block's load is 718 bytecodes of fast path.

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
`JvmLispCompilerTest.fusedIntegerExpressionTreesMatchTheGenericPath` (the fallback's method and
the handler too: `wide` and its siblings bail into `_fxb$N` from every kind of fused method),
`.aDeclaredIntegerLeafIsCastAndAMethodWithNothingElseToBailOnHasNoFallback` (exact locals too:
`DeclaredIntegerProgram`'s `rho` calls no `_fx$` method and no `_ubRead`, `lanes`' first fused
method answers `J`; declared arrays: `sched` calls its stores' `V` methods and one comparison,
nothing else; the program on both wasm backends in
`WasmLispCompilerIntegrationTest.fusedLocalFunctionsAndUnboxedLocalsMatchTheGenericPath`),
`.fusedArefLeavesReadTheGeneralArraysPackedShapeAndBailForEveryOther` (the twin too: its
reads and stores of the integer, and none for an unpinned or `simple-vector` read),
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
