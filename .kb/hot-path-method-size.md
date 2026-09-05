# Hot-path method size: HotSpot's HugeMethodLimit

**Invariant: no method that runs per evaluated form, or per indirect call, may exceed 8000
bytecodes.** `-XX:+DontCompileHugeMethods` is a HotSpot default and refuses to JIT any method past
`HugeMethodLimit`; it then runs interpreted for the process lifetime. **Nothing reports it** --
it shows only as a program several times slower than it should be. Rank the emitter's output with
`-Drontolisp.jvm.debug-method-sizes=true`. **Split rather than raise a constant.** Three ways to
cross it: a sequence WE EMIT PER SITE; a LIBRARY'S OWN function; a DISPATCH TABLE, which grows
with how much the program loads (the cliff comes from adding a LIBRARY, not editing the method).

## Interpreter
`LispEvaluator.evalCons`'s ~200-case `switch (sym.name())` hit 8209 bytecodes (worth 2.7x). Split
into `evalCons` + `evalConsRareOperator`, the latter answering a private `UNHANDLED` sentinel for
operators it does not claim (also the deliberate fall-throughs `read`, the `floor` family with a
divisor, `reduce`, `sort`). Both halves near 4 KB.

## Dispatch tables (`_invoke_<arity>`, `_lookup`)
- `JvmRuntimeBuilder.buildDispatchMethods` emits one dispatcher per call arity; a variadic
  function matches every arity at or above its required count, so tables grow fast (cl-postgres:
  547 cases / ~15 KB on arity 1).
- `DISPATCH_SEGMENT_BUDGET` and `LOOKUP_SEGMENT_BUDGET` (`JvmEvalRuntimeBuilder`) are **6000**
  (was 24000, set against the 64 KB class-file limit rather than the cliff); cases are id-sorted
  and split into segments under it -- the change worth the most.
- Within a segment a **binary search tree** over sorted funcIds (`emitDispatchTree`) replaces the
  linear chain; past one segment `_invoke_<arity>` becomes a router bisecting segment boundaries
  and tail-calling `_invoke_<arity>$<k>` (`emitSegmentRouter`).
- A `tableswitch`/`lookupswitch` is deliberately NOT used: the emitters, `JvmClassShaker` and
  `StackMapAugmenter` would have to decode variable-length instructions
  (`.kb/stackmap-augmenter.md`, unstarted).

## The body splitter (`_k$N` tail continuations)
`JvmBodyOutliner` drives every defun and lambda body from a QUEUE, so the whole TAIL SPINE (its
own forms plus the body of every trailing `let`/`progn`) is one flat sequence with a split point
between any two items. Past `CODE_BUDGET` (**6000**) the rest moves into a fresh
`private static _k$N`; the caller loads the live locals, `invokestatic`s it, and its value IS the
enclosing method's value. (ironclad `update-sha512-block`: 17,003 -> 6,146 + 6,071 + 4,838.)

- A split is taken only where every remaining item is in the method's TAIL. A CAPTURED variable
  travels as its `Object[1]` cell; an unboxed local (`.kb/jvm-int-fusion.md`) crosses boxed
  (`_ubRead`).
- Gates (scopes naming a position in THIS frame): non-empty operand stack, `blockTargets`,
  `unwindScopes`, `tagbodyScopes`, `spillScopes`, or a live set past `MAX_CONTINUATION_PARAMS`
  (**200**, under the JVM's 255 argument slots).
- **Nothing is spliced or reordered**, so a body under budget is byte-identical to the nested-loop
  emission this replaced. No split point inside **a branch**: a tail `cond` chain is ONE item.

## The branch cut (`compiler/AstOutliner`)
An oversized evaluated sub-form is rewritten, before `CrossLambdaExitLowering`, into
`(let ((__outlined_N (lambda () F))) (funcall __outlined_N))`; closure conversion, exit lowering
(`%nlx-throw`) and the backend then finish the job. It lives in `compiler/` because the WASM
chunker can adopt it.

- **A `let`-bound lambda, NOT `flet`**: `flet` gives its local a CL-mandated `block`, a
  `block`-wrapped body is ONE item, and `JvmBodyOutliner` would find nothing to cut inside.
- **The two splitters compose**: this cuts a BRANCH and cannot cut a run of statements;
  `JvmBodyOutliner` the reverse. A sequence buried in a branch arm is reachable by neither until
  the arm becomes a method.
- **The budget is measured, not predicted** (3.4-39 bytecodes per AST node).
  `JvmLispCompiler.compile` reports an oversized defun through a `MethodTooLarge` signal and
  re-runs with that function's measured bytes-per-node ratio applied to a 6000-byte target; still
  over comes back at two thirds, down to a **2000-byte floor**. Rides the `GateUnderpredicted`
  retry loop, so a program with no oversized method is byte-identical. **Only a DEFUN is
  reported** (a lambda's `_lambda_<funcId>` cannot be pointed back at a form), and
  `lack/util:find-package-or-load` answers `pieces=0` -- no branch to cut, stays marginally over.
- Two pre-existing backend bugs this shape can hit: a captured `let` variable assigned inline in a
  sibling arm compiles to a raw store (WRONG ANSWER), and a method past 255 local slots emits a
  truncated index.

## The sequences we emit per site
One method per class, built on first use; the JIT inlines the static call.

- **`_hbGuard`** (`JvmHandlerCaseCompiler.guardLandingPad`): every `handler-bind` wrapped its body
  in a ~500-bytecode `%hb-guard` landing pad. It is `(Throwable)Throwable` and the site is
  `invokestatic; athrow`. Under RESTART MODE `restart-case` expands through `handler-bind`.
- **Type predicates** (`JvmEmitHelper.emitSharedCall`): `_pAtom`, `_pConsp`, `_pListp`, `_pStringp`
  (~90 bytecodes each), `_pEq`, `_pEql` (~45); a site is four bytes. The GENERATED dispatches
  (`%typep-runtime`, `%error-runtime`, `%sbr-*`, `%mmi-*`, `%slot-value-runtime`) are 40-57-clause
  `cond`s of these -- all of them (8.5-13.8 KB) went under the cliff at once.
- **`_ql$N`** (`JvmQuoteCompiler`): a quoted list costs ~15 bytecodes a cell. Past
  `2 * QUOTE_CHUNK_BUDGET` the spine is cut into `QUOTE_CHUNK_BUDGET` (**2000**) estimated-byte
  chunks, each a `(Object)Object` builder taking the tail so far. Construction moves, it is not
  memoized.

Not covered: `Jvm/WasmExprCompiler.compileCons` (19-20 KB) and the two `compile` methods (10 KB)
run once per AST node at COMPILE time and stay over.

## Tests
- `LispEvaluatorHotMethodSizeTest`; `JvmLibraryMethodSizeTest` (ironclad compile, plus a
  clack/ningle case via `JvmSourceCompiler` gated on `RONTOLISP_NINGLE_E2E=1`). **Neither case
  allows a method by name**; only `_top$0` / `_top$1` / `<clinit>` are over.
- `JvmLispCompilerTest.aBranchArmPastTheMethodSizeBudgetBecomesItsOwnMethod`,
  `.aFunctionBodyPastTheMethodSizeBudgetSplitsIntoTailContinuations`,
  `.aSplitFunctionBodyCarriesItsUnboxedLocalsAcross`.
