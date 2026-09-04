# Hot-path method size: HotSpot's HugeMethodLimit

**Invariant: no method that runs per evaluated form, or per indirect call, may exceed 8000
bytecodes.** `-XX:+DontCompileHugeMethods` is a HotSpot default and refuses to JIT ANY
method above `HugeMethodLimit` (8000 bytes of bytecode); such a method runs interpreted for
the process lifetime. **Nothing reports it** -- no warning, no flag in a stack trace, every
functional test passes; it shows only as a program several times slower than it should be.

Three ways to cross it, cheapest fix first:

1. **A sequence WE EMIT PER SITE** (a landing pad, a type predicate, a literal's cells) --
   ours entirely, and fixing it shrinks every method at once. Measure first with
   `-Drontolisp.jvm.debug-method-sizes=true`, which ranks what the emitter produced.
2. **A LIBRARY'S OWN function** compiled by us into something larger than the limit -- the
   split is ours to make.
3. **A DISPATCH TABLE**, which grows with how much the program loads, so the cliff comes
   from adding a LIBRARY, not from editing the method.

## `LispEvaluator.evalCons` (the interpreter)

The operator table -- a `switch (sym.name())` over ~200 case labels -- reached 8209
bytecodes, so the interpreter's innermost method was never compiled (worth 2.7x on an
arithmetic-heavy program). Split into `evalCons` (first half) and `evalConsRareOperator`
(second half, answering the private `UNHANDLED` sentinel for an operator it does not claim,
which also covers the arms that deliberately fall through to an ordinary function call:
`read`, the `floor` family with a divisor, `reduce`, `sort`). Both halves sit near 4 KB.
`LispEvaluatorHotMethodSizeTest` fails the build if ANY method of `LispEvaluator` crosses
the limit. **Split again rather than raising the constant.**

## `_invoke_<arity>` and `_lookup` (dispatch tables)

`JvmRuntimeBuilder.buildDispatchMethods` emits one dispatcher per call arity, one case per
callable of that arity -- and a variadic function matches every arity at or above its
required count, so tables grow fast (at cl-postgres scale the arity-1 table alone held 547
cases / ~15 KB, so every `funcall`, generic-function call and SHA-256 `rol32` ran
interpreted). Two changes in `JvmRuntimeBuilder`:

- `DISPATCH_SEGMENT_BUDGET` is **6000** (it was 24000, chosen against the 64 KB class-file
  method limit rather than the cliff). Cases are id-sorted and split into segments under it.
  This is the change worth the most.
- Within a segment, a **binary search tree** over the sorted funcIds (`emitDispatchTree`)
  replaces the linear `if (id == funcId)` chain; with more than one segment
  `_invoke_<arity>` becomes a router bisecting segment boundaries and tail-calling
  `_invoke_<arity>$<k>` (`emitSegmentRouter`). Case bodies are rendered once, branch-free,
  so they can be spliced anywhere in the tree.

`_lookup` (`JvmEvalRuntimeBuilder`), the name-to-funcId registry every late-bound designator
resolves through, had the same shape and the same bug: `LOOKUP_SEGMENT_BUDGET` is **6000**
now too.

A true O(1) `tableswitch`/`lookupswitch` is available and is NOT used: the emitters,
`JvmClassShaker` and `StackMapAugmenter` would all have to decode those variable-length
instructions ([stackmap-augmenter.md](stackmap-augmenter.md) lists it as unstarted). Binary
search is ~10 comparisons at 600 callables against the ~300 the chain averaged.

## The body splitter (`_k$N` tail continuations)

`JvmBodyOutliner` drives every defun and lambda body from a QUEUE of pending items instead
of a nested loop per construct, so the function's whole TAIL SPINE -- its own body forms and
the body of every `let`/`progn` (hence `flet`/`labels`/`let*`/`locally`) that ends it -- is
one flat sequence with a split point between any two items. Past `CODE_BUDGET` (**6000**)
the remaining items move into a fresh `private static _k$N`; the caller loads the live
locals, `invokestatic`s it, and the value it answers IS the enclosing method's value.
(ironclad's `update-sha512-block`, 17,003 bytecodes, becomes 6,146 + 6,071 + 4,838.)

What makes passing the locals forward enough -- no multiple return, no boxes:

- A split is taken only where every remaining item is in the method's TAIL. Only
  compile-time scope restores and stack-neutral special-binding restores may follow, and
  those run in the caller once the continuation returns.
- A CAPTURED variable travels as its `Object[1]` cell, not its value, so a closure built
  before the split and the continuation share one cell.
- An unboxed dual-representation local (`.kb/jvm-int-fusion.md`) has no parameter its raw
  slot can travel in: it crosses boxed (`_ubRead`) and lands as an ordinary local.
- Gates -- the scopes naming a position in THIS frame: a non-empty operand stack,
  `blockTargets`, `unwindScopes`, `tagbodyScopes`, `spillScopes`, or a live set past
  `MAX_CONTINUATION_PARAMS` (**200**, under the JVM's 255 argument slots).

**Nothing is spliced or reordered**: an item is emitted exactly as the construct's own loop
would have (`compileExpr` + `pop` for a `let` body, `compileForEffect` for a `progn`'s), so
a body under budget is byte-identical to the nested-loop emission this replaced.

One shape has no split point here, because the spine is linear and a split point is between
two ITEMS: **a branch**. A `cond` chain in tail position is one `if` item; splitting inside
it would need both arms to carry the same continuation, i.e. emitting it twice.

## The branch cut (`compiler/AstOutliner`)

An oversized evaluated sub-form is rewritten, before `CrossLambdaExitLowering` runs, into
the `let`-bound lambda an `flet` expands into:

```lisp
(let ((__outlined_N (lambda () F))) (funcall __outlined_N))
```

Every existing mechanism then finishes the job: closure conversion captures and BOXES what
`F` assigns, the exit lowering turns a crossing `go`/`return-from` into a `%nlx-throw` the
establishing frame catches, and the backend emits `F` as its own method. Neither backend
learns a new form; the pass lives in `compiler/` because the WASM chunker has the same
problem and can adopt it.

- **The wrapper is a `let`-bound lambda and NOT `flet`** on purpose: `flet` gives its local
  the block CL mandates, a body wrapped in `block` is ONE item, and `JvmBodyOutliner` would
  find nothing to cut inside the piece. (Same reason an over-budget piece that IS a sequence
  is still outlined -- moving it hands it to the other splitter.)
- **The two splitters compose and each covers what the other cannot**: this one cuts a
  BRANCH and cannot cut a run of statements; `JvmBodyOutliner` cuts a run of statements only
  along a tail spine. A sequence buried in a branch arm is reachable by neither until the
  arm becomes a method.
- **The budget is measured, not predicted.** Bytecodes per AST node ranges 3.4 to 39 across
  one program's methods (surface macros `loop`/`cond`/`case`/`handler-bind` expand during
  the backend's own pass), so an absolute node budget is useless. `JvmLispCompiler.compile`
  emits every body, reports any defun over the limit through a `MethodTooLarge` signal, and
  re-runs the compile with that function's emitted size and a 6000-byte target; the node
  budget is the function's own measured bytes-per-node ratio applied to that target, and a
  function still over comes back with a target two thirds as large, down to a **2000-byte
  floor**. This rides the `GateUnderpredicted` retry loop; a program with no oversized
  method never runs the pass, so everything else is byte-identical.
- **Only a DEFUN is reported**: a lambda's generated name (`_lambda_<funcId>`) cannot be
  pointed back at a form for the next attempt.
- Known unreached shape: `lack/util:find-package-or-load` at 8,125-8,193 bytes answers
  `pieces=0` -- 60 AST nodes, almost all two quoted literals the `_ql$N` chunking already
  cut as far as it goes. No branch to cut, so it stays marginally over.
- Two pre-existing backend bugs the shape this pass creates on purpose can hit: a captured
  `let` variable assigned inline in a sibling arm compiles to a raw store (a WRONG ANSWER),
  and a method past 255 local slots emits a truncated index.

## The sequences we emit per site

Each is now one method per class, built on first use; the shared helper is a static call the
JIT inlines, so this costs nothing at run time.

- **`_hbGuard`** (`JvmHandlerCaseCompiler.guardLandingPad`). Every `handler-bind` wraps its
  body in a `%hb-guard` landing pad of ~500 bytecodes (read and clear the condition channel,
  synthesize the instance of a condition-less throw, run the cluster stack unless it already
  ran for this instance, restore the channel, rethrow). It reads nothing but the caught
  throwable, so it takes one -- `(Throwable)Throwable`, answering what to rethrow -- and the
  site is `invokestatic; athrow`. In RESTART MODE `restart-case` expands through
  `handler-bind`, so a `check-type` inside a macro used forty times carried forty pads.
- **The type predicates** (`JvmEmitHelper.emitSharedCall`): `_pAtom`, `_pConsp`, `_pListp`,
  `_pStringp` (each deciding over a dozen host classes, ~90 bytecodes) and `_pEq`, `_pEql`
  (~45, with their own nil handling). A site is four bytes. This is what the GENERATED
  dispatches are made of -- `%typep-runtime`, `%error-runtime`, the `%sbr-*` slot-boundp
  chain, `%mmi-*`, `%slot-value-runtime` are a `cond` over 40-57 clauses each testing a list
  with `atom`/`eql` -- and it took all of them (8.5-13.8 KB) under the cliff at once.
- **`_ql$N`** (`JvmQuoteCompiler`). A quoted list is built tail-first, ~15 bytecodes a cell
  plus its car. Past `2 * QUOTE_CHUNK_BUDGET` the spine is cut into chunks of
  `QUOTE_CHUNK_BUDGET` (**2000**) estimated bytes, each a `(Object)Object` builder taking
  the tail so far and answering its chunk's head. The cells are still fresh at every
  evaluation -- this moves the construction, it does not memoize it.

## Not covered by the invariant

`Jvm/WasmExprCompiler.compileCons` (19-20 KB) and the two `compile` methods (10 KB) are past
the limit and stay there: they run once per AST node at COMPILE time.

## Pinning tests

- `LispEvaluatorHotMethodSizeTest` -- no method of `LispEvaluator` crosses the limit.
- `JvmLibraryMethodSizeTest` -- no emitted method of the ironclad-loading compile crosses
  it (the guard over LIBRARY code, which the splitter must keep true). Its second case runs
  the same guard over the clack/ningle stack (`examples/net/httpbin-ningle.lisp` through
  `JvmSourceCompiler`), gated on `RONTOLISP_NINGLE_E2E=1` since it needs the Quicklisp
  cache. **Neither case allows a method by name**: the compiler MAKES the invariant true
  rather than the test policing exceptions. Only the once-per-process `_top$0` / `_top$1` /
  `<clinit>` are over the limit in that compile.
- `JvmLispCompilerTest.aBranchArmPastTheMethodSizeBudgetBecomesItsOwnMethod` -- the branch
  cut over a body with no tail spine, pinning that the arm's assignment to an enclosing
  variable survives the move and the `go` leaving the arm still reaches its label.
- `JvmLispCompilerTest.aFunctionBodyPastTheMethodSizeBudgetSplitsIntoTailContinuations`
  (running values, a closure built before the split reading variables mutated after it, a
  special binding whose restore is emitted past the continuation's return) and
  `aSplitFunctionBodyCarriesItsUnboxedLocalsAcross`.
