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

One shape has no split point HERE, because the spine is linear and a split point
is between two ITEMS: **a branch**. A `cond` chain in tail position is one `if`
item; splitting inside it would need both arms to carry the same continuation,
which means emitting it twice.

That is what `fast-http`'s two parsers were -- 37,913 bytecodes for
`parse-header-field-and-value` (down from 56,513 with the per-site work below)
and 30,977 for `http-multipart-parse`. NOT tagbody state machines, which is what
they look like from the source: `proc-parse`'s `match-i-case` generates a
decision tree over the header bytes -- one `if` per character position per
spelling -- with the whole "not one of ours" continuation (`handle-otherwise`:
scan to the colon, skip the spaces, parse the value) duplicated at every one of
them. The body is 9,801 AST nodes holding 143 `if`s, 141 `go`s and only 3
tagbodies, and the emitted bytes are spread evenly over all of it: after the
per-site work below no single operator accounts for even 15% of the method.

## The branch cut (`compiler/AstOutliner`)

Cutting a branch means a `go` or a `return-from` that LEAVES the arm stops being
a jump. Doing it at the AST level is what makes that somebody else's problem: an
oversized evaluated sub-form is rewritten, before `CrossLambdaExitLowering` runs,
into the `let`-bound lambda an `flet` expands into --

```lisp
(let ((__outlined_N (lambda () F))) (funcall __outlined_N))
```

-- and every existing mechanism finishes the job. Closure conversion captures and
BOXES what `F` assigns, the exit lowering turns a crossing `go`/`return-from`
into a `%nlx-throw` the establishing frame catches, and the backend emits `F` as
its own method. Neither backend learns a new form; the pass is in `compiler/`
because the WASM chunker has the same problem and can adopt it.

**The two splitters compose, and each covers what the other cannot.** This one
cuts a BRANCH and cannot cut a run of statements; `JvmBodyOutliner` cuts a run of
statements and only along a method's tail spine. So a sequence buried in a branch
arm is reachable by neither -- until the arm becomes a method, and its statements
ARE a tail spine. That is why the wrapper is spelled as a `let`-bound lambda
rather than as `flet`: `flet` gives its local the block CL mandates, a body
wrapped in a `block` is ONE item, and the splitter would find nothing to cut
inside the piece. (It is also why an over-budget piece is still outlined when it
is a sequence -- moving it is what hands it to the other splitter.)

**The budget is measured, not predicted.** Bytecodes per AST node ranges from 3.4
to 39 across one program's methods, because the surface macros (`loop`, `cond`,
`case`, `handler-bind`) expand during the backend's own pass and the node count
at AST time does not see it -- so an absolute node budget is useless. Instead
`JvmLispCompiler.compile` MEASURES: every body is emitted, any defun over the
limit is reported through a `MethodTooLarge` signal, and the compile is re-run
with that function's emitted size and a 6000-byte target. The node budget is the
function's own measured bytes-per-node ratio applied to that target, and the next
compile verifies it -- a function still over comes back with a target two thirds
as large, down to a 2000-byte floor. This rides the retry loop
`GateUnderpredicted` already established, and a program with no oversized method
never runs the pass at all, so everything else is byte-identical.

Only a DEFUN is reported: a lambda's generated name (`_lambda_<funcId>`) cannot
be pointed back at a form for the next attempt to cut.

What it does NOT reach, measured over every program in `examples/` (2026-08-29):
the only other method any of them compiles over the limit is
`lack/util:find-package-or-load` at 8,125-8,193 bytes, and the pass answers
`pieces=0` for it -- 60 AST nodes, almost all of it two quoted literals the
`_ql$N` chunking below already cut as far as it goes. There is no branch to cut,
so it stays marginally over; the cost is one wasted compile attempt for the
`tiny-routes` programs, whose output is byte-identical either way.

Two pre-existing backend bugs surfaced while building this, both reproducible
from hand-written Lisp with the pass making no change -- `.todo/561` (a captured
`let` variable assigned inline in a sibling arm compiles to a raw store: a WRONG
ANSWER, and the shape this pass creates on purpose) and `.todo/562` (a method
past 255 local slots emits a truncated index).

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
`package-use-list` / `find-package-or-load`.

fast-http's two parsers were NOT among them -- they stayed at 30-38 KB and cost
nothing measurable here, the same lesson SHA-512 taught: size is the cliff, but
the time has to be there for closing it to show. The branch cut above closed them
anyway, because the invariant is the point (2026-08-29):

| method | before | after |
| --- | --- | --- |
| `FAST-HTTP.PARSER::PARSE-HEADER-FIELD-AND-VALUE` | 37,913 | under 4,000 |
| `FAST-HTTP.MULTIPART-PARSER:HTTP-MULTIPART-PARSE` | 30,977 | 4,088 |

and the request loop is unchanged, as predicted: 6.07 s per 10,000 requests
against 6.51 s for the same build without the cut, medians of interleaved runs
whose spread is ±0.4 s. The emitted class got SMALLER (3,579,492 -> 3,527,967
bytes): a piece emitted once carries one set of stack map frames. NO method of
the clack/ningle compile is over the limit now except the once-per-process
`_top$0` / `_top$1` / `<clinit>`, so `JvmLibraryMethodSizeTest` carries no
by-name allowance at all.

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
  it needs the Quicklisp cache, so it is gated on `RONTOLISP_NINGLE_E2E=1`.
  Neither case allows a method by name: the compiler now MAKES the invariant
  true rather than the test policing a list of exceptions.
- `JvmLispCompilerTest.aBranchArmPastTheMethodSizeBudgetBecomesItsOwnMethod` --
  the branch cut over a body with no tail spine, pinning that the arm's
  assignment to an enclosing variable survives the move and that the `go`
  leaving the arm still reaches its label.
- `JvmLispCompilerTest.aFunctionBodyPastTheMethodSizeBudgetSplitsIntoTailContinuations`
  (the running values, a closure built before the split reading variables
  mutated after it, and a special binding whose restore is emitted past the
  continuation's return) and `aSplitFunctionBodyCarriesItsUnboxedLocalsAcross`
  (the raw local that crosses boxed).
