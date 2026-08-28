# Hot-path method size: HotSpot's HugeMethodLimit

**Invariant: no method that runs per evaluated form, or per indirect call, may
exceed 8000 bytecodes.** `-XX:+DontCompileHugeMethods` is a HotSpot default and
refuses to JIT-compile ANY method above `HugeMethodLimit` (8000 bytes of
bytecode); such a method runs in the bytecode interpreter for the lifetime of the
process. Nothing reports it -- no warning, no flag in a stack trace, and every
functional test still passes. It shows up only as a program that is several times
slower than it should be, which is exactly how it hid twice (todo 188).

There are three ways to cross it. A DISPATCH TABLE grows with how much the
program loads, so the cliff comes from adding a LIBRARY, not from editing the
method. A LIBRARY'S OWN function is compiled by us into something larger than the
limit -- the split is ours to make, because the library has no say in how big its
function's bytecode comes out. And a SEQUENCE WE EMIT PER SITE is ours entirely:
a landing pad, a type predicate, a literal's cells, written once in the source
and a hundred times in the bytecode. The last is the cheapest to fix and helps
every method at once, so it is the first thing to measure when a function is over
(`-Drontolisp.jvm.debug-method-sizes=true` ranks what the emitter produced).

## `LispEvaluator.evalCons` (the interpreter)

The operator table -- a `switch (sym.name())` over ~200 case labels -- had reached
8209 bytecodes, so the interpreter's innermost method was never compiled. Split
into `evalCons` (the first half) and `evalConsRareOperator` (the second half,
answering the private `UNHANDLED` sentinel for an operator it does not claim,
which also covers the arms that deliberately fall through to the ordinary
function call: `read`, the `floor` family with a divisor, `reduce`, `sort`).
Worth **2.7x** on an arithmetic-heavy program by itself.

Adding an operator adds bytecode to whichever half holds it. Both halves sit near
4 KB today; `LispEvaluatorHotMethodSizeTest` parses the compiled class and fails
the build if ANY method of `LispEvaluator` crosses the limit. Split again rather
than raising the constant.

## `_invoke_<arity>` (the JVM backend's indirect-call dispatch)

`JvmRuntimeBuilder.buildDispatchMethods` emits one dispatcher per call arity,
holding a case per callable of that arity -- and a variadic function matches every
arity at or above its required count, so the tables grow fast. At cl-postgres
scale the arity-1 table alone held 547 cases / ~15 KB, well past the cliff, and
every indirect call in the program (SHA-256's `rol32`, every `funcall`, every
generic-function call) ran interpreted.

Two changes, both in `JvmRuntimeBuilder`:

- `DISPATCH_SEGMENT_BUDGET` is **6000**, below the 8000 cliff (it used to be
  24000, chosen only against the 64 KB class-file method limit). Cases are
  id-sorted and split into segments under that budget.
- Within a segment the old linear `if (id == funcId)` chain is now a **binary
  search tree** over the sorted funcIds (`emitDispatchTree`), and with more than
  one segment `_invoke_<arity>` becomes a router that bisects the segment
  boundaries and tail-calls `_invoke_<arity>$<k>` (`emitSegmentRouter`). Case
  bodies are rendered once, branch-free, so they can be spliced anywhere in the
  tree.

Both matter, but the budget is the one worth the most: `-XX:-DontCompileHugeMethods`
alone recovered 2.8x on the measured workload, i.e. nearly all of it.

`_lookup` (`JvmEvalRuntimeBuilder`), the name-to-funcId registry every late-bound
function designator resolves through, is the same shape and had the same bug:
`LOOKUP_SEGMENT_BUDGET` was 24000 -- again the 64 KB method cap rather than the
cliff -- so a clack/ningle program carried four chained segments of 24 KB. It is
**6000** now, like the dispatch segments.

A true O(1) `tableswitch`/`lookupswitch` is still available and is NOT used: the
emitters, `JvmClassShaker` and `StackMapAugmenter` would all have to learn to
decode those variable-length instructions
([stackmap-augmenter.md](stackmap-augmenter.md) lists it as unstarted). Binary
search costs ~10 comparisons at 600 callables versus one, which is noise next to
the ~300 the chain averaged, and needs no new opcode anywhere in the toolchain.

## The measurement that pins it

PBKDF2-HMAC-SHA256, 4096 iterations, ironclad from Quicklisp (2026-07-27,
linux/x86-64, exec jar, wasmtime 47):

| | ironclad alone | inside the cl-postgres stack | tax |
| --- | --- | --- | --- |
| JVM before | 4.9 s | 20.4 s | 4.2x |
| JVM after | 0.70 s | 0.83 s | **1.02x** |

The control that isolates the mechanism: 1200 defuns that are DEFINED and
referenced as values but never called (so they only enlarge the dispatch tables)
leave the JVM unchanged (838 -> 795 ms) and cost the WASM backend 2.0x (7.3 ->
14.3 s) -- see the open item in `.todo/188`, whose `br_table` dispatcher still
carries every case body inline in one function.

## The body splitter (`_k$N` tail continuations)

The two sites above are OURS; a library's own function is the third way to cross
the cliff, and no amount of tuning our tables fixes it. ironclad's
`update-sha512-block` -- 80 fully unrolled SHA-512 rounds -- compiled to 17,003
bytecodes here and ran interpreted from the day it first compiled (todo 526).

`JvmBodyOutliner` drives every defun and lambda body from a QUEUE of pending
items instead of a nested loop per construct, so the function's whole TAIL SPINE
-- its own body forms, and the body of every `let`/`progn` (hence
`flet`/`labels`/`let*`/`locally`, which lower to those) that ends it -- is one
flat sequence with a split point between any two items. Past
`CODE_BUDGET` (6000, the same margin the dispatch segments keep) the remaining
items move into a fresh `private static _k$N`, the caller loads the live locals,
`invokestatic`s it, and the value it answers IS the enclosing method's value.
`update-sha512-block` becomes 6,146 + 6,071 + 4,838.

What makes passing the locals forward enough -- no multiple return, no boxes:

- A split is only taken where every remaining item is in the method's TAIL. Only
  compile-time scope restores and the stack-neutral special-binding restores may
  follow, and those run in the caller once the continuation returns, so nothing
  after the call can observe the caller's copy of a variable the continuation
  mutated.
- A CAPTURED variable travels as its `Object[1]` cell, not its value, so a
  closure built before the split and the continuation share one cell.
- An unboxed dual-representation local (`.kb/jvm-int-fusion.md`) has no
  parameter its raw slot can travel in: it crosses boxed (`_ubRead`) and lands
  as an ordinary local.
- The gates are the scopes that name a position in THIS frame: a non-empty
  operand stack, `blockTargets`, `unwindScopes`, `tagbodyScopes` or
  `spillScopes` decline the split, as does a live set past
  `MAX_CONTINUATION_PARAMS` (200, under the JVM's 255 argument slots).

**Nothing is spliced or reordered**: an item is emitted exactly as the
construct's own loop would have emitted it (`compileExpr` + `pop` for a `let`
body, `compileForEffect` for a `progn`'s), so a body that never crosses the
budget is byte-identical to the nested-loop emission this replaced. Verified
over the 220 programs of `examples/` and `src/test/resources/` that compile
standalone: 210 byte-identical, 10 differing, and every one of those 10 differs
because a method really did split.

One shape still has no split point, because the spine is linear and a split
point is between two ITEMS: **a branch**. A `cond` chain in tail position is one
`if` item; splitting inside it would need both arms to carry the same
continuation, which means emitting it twice.

That is what `fast-http`'s two parsers are -- 37,913 bytecodes for
`parse-header-field-and-value` (down from 56,513 with the per-site work below)
and 30,509 for `http-multipart-parse` (from 32,183). NOT tagbody state machines,
which is what they look like from the source: `proc-parse`'s `match-i-case`
generates a decision tree over the header bytes -- one `if` per character
position per spelling -- with the whole "not one of ours" continuation
(`handle-otherwise`: scan to the colon, skip the spaces, parse the value)
duplicated at every one of them. The body is 9,801 AST nodes holding 143 `if`s,
141 `go`s and only 3 tagbodies, and the emitted bytes are spread evenly over all
of it: after the per-site work below no single operator accounts for even 15% of
the method. Cutting it needs a splitter that can put a BRANCH ARM in its own
method, which means a `go` or a `return-from` that leaves the arm has to become
something other than a jump -- at the AST level (an `flet`, which the existing
cross-lambda exit lowering already rewrites), or as a state machine the
enclosing method dispatches over. Both are open.

Splitting is what the SIZE cliff needs, and it is not by itself a speed-up: what
the JIT then compiles has to be where the time goes. On SHA-512 it is not --
`-XX:-DontCompileHugeMethods` on the unsplit build measures no difference,
because the body's glue is 4% of the run and `new BigInteger(String)` per
`#xFFFFFFFFFFFFFFFF` literal is 40% (`.todo/557`).

## The sequences we emit per site

Nothing above helps a method whose size is one sequence OF OURS written once per
site. Three were, and each is now one method per class, built on first use:

- **`_hbGuard`** (`JvmHandlerCaseCompiler.guardLandingPad`). Every
  `handler-bind` wraps its body in a `%hb-guard` landing pad, and the pad is
  ~500 bytecodes: read and clear the condition channel, synthesize the instance
  of a condition-less throw (one construction per raw-failure class), run the
  cluster stack unless it already ran for this instance, restore the channel,
  rethrow. It reads nothing but the caught throwable, so it takes one
  (`(Throwable)Throwable`, answering what to rethrow) and the site is
  `invokestatic; athrow`. In RESTART MODE `restart-case` expands through
  `handler-bind`, so a `check-type` inside a macro used forty times carried
  forty pads: 18,600 bytecodes of `parse-header-field-and-value` alone.
- **The type predicates** (`JvmEmitHelper.emitSharedCall`). `atom`, `consp`,
  `listp` and `stringp` each decide over a dozen host classes (~90 bytecodes),
  and `eq`/`eql` carry their own nil handling (~45); all of them depend on
  nothing but the values. `_pAtom`, `_pConsp`, `_pListp`, `_pStringp`, `_pEq`
  and `_pEql` are those bodies, and a site is four bytes. This is what the
  GENERATED dispatches are made of -- `%typep-runtime`, `%error-runtime`, the
  `%sbr-*` slot-boundp chain, `%mmi-*`, `%slot-value-runtime` are a `cond` over
  40-57 clauses each testing a list with `atom`/`eql` -- and it took every one of
  them (8.5-13.8 KB) under the cliff at once.
- **`_ql$N`** (`JvmQuoteCompiler`). A quoted list is built tail-first, cell by
  cell, ~15 bytecodes each plus its car; a table literal is thousands. Past
  `2 * QUOTE_CHUNK_BUDGET` the spine is cut into chunks of `QUOTE_CHUNK_BUDGET`
  (2000) estimated bytes, each a `(Object)Object` builder taking the tail so far
  and answering its chunk's head. The cells are still fresh at every evaluation
  -- this moves the construction, it does not memoize it. `package-use-list` and
  `lack/util:find-package-or-load` were 10-12 KB of which ~9.5 KB was two
  literals.

The shared helper is a static call the JIT inlines, so this costs nothing at run
time and is the FIRST thing to try: it shrinks every method that writes the
sequence, not one.

## The clack/ningle measurement

`examples/net/httpbin-ningle.lisp` compiled with `-o Prog.class`, one keep-alive
connection, 1000 warm-up requests then 10,000 timed (2026-08-28, linux/x86-64):

| | 10,000 requests | with `-XX:-DontCompileHugeMethods` |
| --- | --- | --- |
| before | 11.2 s | 6.5 s |
| after | **6.2 s** | 6.4 s |

**1.8x**, and the second column is what says the work is done: the flag no longer
buys anything, because nothing on the request path is over the limit any more.
What was over it: the CLOS runtime every ningle controller runs through
(`%slot-value-runtime`, `%slot-boundp-runtime`, `%typep-runtime`, the `%sbr-*`
and `%mmi-*` dispatches), `_lookup`'s four 24 KB segments, and
`package-use-list` / `find-package-or-load`. NOT fast-http's two parsers, which
are still 30-38 KB and cost nothing measurable here -- the same lesson SHA-512
taught: size is the cliff, but the time has to be there for closing it to show.

## Not covered by the invariant

`Jvm/WasmExprCompiler.compileCons` (19-20 KB) and the two `compile` methods
(10 KB) are past the limit and stay there: they run once per AST node at COMPILE
time, where an interpreted run costs a fraction of a second on the whole program,
not a factor on every evaluated form.

## Pinning tests

- `LispEvaluatorHotMethodSizeTest` -- no method of `LispEvaluator` crosses the
  limit.
- `JvmLibraryMethodSizeTest` -- no emitted method of the ironclad-loading
  compile crosses it either, which is the guard over LIBRARY code and what the
  splitter has to keep true. Its second case runs the same guard over the
  clack/ningle stack (`examples/net/httpbin-ningle.lisp` through
  `JvmSourceCompiler`), which is where an HTTP request's whole hot path lives;
  it needs the Quicklisp cache, so it is gated on `RONTOLISP_NINGLE_E2E=1`. The
  two fast-http parsers are named there with the size they compile to today, so
  the shape that has no split point cannot GROW while it waits for one.
- `JvmLispCompilerTest.aFunctionBodyPastTheMethodSizeBudgetSplitsIntoTailContinuations`
  (the running values, a closure built before the split reading variables
  mutated after it, and a special binding whose restore is emitted past the
  continuation's return) and `aSplitFunctionBodyCarriesItsUnboxedLocalsAcross`
  (the raw local that crosses boxed).
