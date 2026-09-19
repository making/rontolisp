# `do`/`return` and the `%block` non-local exit boundary

`do` is a macro (`LispMacroExpander.expandDo`) expanding to a `let`/`while` loop.
`do`/`dolist`/`dotimes` wrap their expansion in the internal `%block`
(`LispNames.BLOCK_INTERNAL`, `CL_INTERNALS`); `return` (`LispNames.RETURN`) exits to the
**nearest** enclosing `%block`. `member`/`assoc` expand through `do`/`return`. The runtime
`_eval` interpreters know none of these forms.

## Plain `return`
Interpreter: `BlockReturnSignal` (stack-trace-free) at the scope `evalBlock` established.
JVM (`JvmBlockCompiler`/`JvmReturnCompiler`, `Ctx.blockTargets`): store-then-`goto`, reaching
the exit with the stack the block was *entered* with (`BlockTarget.entryStack`) — the verifier
requires it, so a `return` mid-expression **discards** the abandoned expression's operands
(`JvmReturnCompiler.emitStackUnwind`, `.kb/error-handling.md`). WASM
(`WasmBlockCompiler`/`WasmReturnCompiler`): `br` at
`Ctx.wasmCtrlDepth - marker` (bumped only by `if` +1, `while` +2), which discards excess
operands for free.

## `block`/`return-from` on the INTERPRETER is LEXICAL, like the compile path
- `runBlock` runs the body in its own `Environment` marked with the block name
  (`Environment.installBlock`); **that scope object IS the block's identity**, one per
  activation. `blockExit` resolves up the LEXICAL chain (`Environment.findBlock`). The nil
  block is an ordinary name (`NIL_BLOCK` = `"NIL"`), so named blocks in between are transparent
  to a plain `return` for free.
- **Not dynamic on purpose**: a `handler-bind` handler runs at the SIGNAL point, so a
  nearest-active-frame lookup let the signalling function's own loop catch rove's `signals`
  and return the CONDITION. For a dynamic exit use `catch`/`throw`.
- `evalDefun` wraps the body (rewrite SKIPPED via 3-arg `LambdaLists.expand(..., false)`) in
  `(block <function-name> ...)`, `expandDefmethod` wraps method bodies, **lambdas get NO
  block** as in CL. A sole block form (`LispEvaluator.soleBlockForm`) installs on the CALL's own
  scope. `(loop named foo ...)` wraps in `(block foo ...)` with `%block` still inside.

## Named `block`/`return-from` on the COMPILE PATH (JVM + wasm-GC, LEXICAL)
- **`%fn-block`** (`LispNames.FN_BLOCK_INTERNAL`): `LambdaLists.wrapReturnFrom` wraps a
  `return-from`-containing body; the scan stops at nested `lambda`/`defun`
  (`containsReturnFrom`).
- `JvmLispCompiler.BlockTarget` / `WasmLispCompiler.BlockMarker` carry
  `(name, catchesPlain, functionBoundary)`. Plain `return` takes the nearest `catchesPlain`
  block, SKIPPING named blocks; `(return-from name v)` takes the nearest matching name, falling
  back to the nearest `functionBoundary` (`Jvm`/`WasmReturnFromCompiler`;
  `LispMacroExpander.blockName` handles designators).
- Unwind: `JvmReturnCompiler.emitExit` generalizes the exit to any target depth (comparisons
  use the target's 1-based block-stack depth, not the stack size); wasm INLINES escaped
  cleanups for `return-from` — same limit as `go` (a throw from an inlined cleanup can re-enter
  its own handler).

### Cross-lambda `return-from` / `return` = a real non-local exit (EH-based)
`compiler/CrossLambdaExitLowering` (compile-path only, before `desugarProgram`) rewrites the
establishing block to `(let ((id (%nlx-tag))) (%nlx-catch id BODY))` and the crossing exit to
`(%nlx-throw id v)`; the lexical fast path stays for a same-function exit. Covers
`flet`/`labels` bodies and a bare `(return [v])` against the nearest NIL-BLOCK scope. The `id`
is a **dynamic block-instance id** captured by `FreeVarAnalyzer`, minted fresh per activation.
- Transport: JVM a `RuntimeException` + the `_nleTl` `{throwable,id,value,previous}` channel
  (`JvmNlxCompiler`); wasm a `$block-exit` tag carrying `(id . value)` (`WasmNlxCompiler`,
  tag 1, gated).
- **On wasm the id is an i31 VALUE** (`NLX_ID_CTR` cell, `ref.eq` = value equality),
  snapshotted at region entry. **Never a fresh GC struct compared by identity**: that broke at
  cl-ppcre scale, shape-dependently and engine-divergently. JVM object identity is sound.
- **The JVM channel is a STACK, not a slot**: a cleanup completing an exit OF ITS OWN used to
  clear it, so the outer exit found nothing at its landing pad and the first enclosing
  `handler-case`/`%hb-guard` turned it into a message-less `simple-error`.
- **`handler-case` does NOT intercept it**: the JVM handler rethrows a pending `_nleTl` first;
  wasm uses a distinct tag with a block-exit passthrough restoring the handler depth.
- EH-mode trigger: a cross-lambda exit compiles in EH mode like `handler-case`; a program
  without one is byte-identical. `--no-gc` has no `return-from` at all. Consumers:
  cl-utilities, cl-ppcre (`ClPpcreE2eTest`).

## `catch`/`throw` (dynamic, tag-keyed)
`cl` SPECIAL FORMS (`LispNames.CATCH`/`THROW`, `PackageRegistry.CL_SPECIAL_FORMS`).
**`LispNames.CATCH` is ONE constant serving two operators**: bare `catch` is this special form,
`rontolisp:catch` (`LispNames.CATCH_QUALIFIED`) the unrelated future combinator — the package
qualification, not the constant, tells them apart.
- Interpreter `evalCatch`/`evalThrow`: `ThrowSignal(tag, value)`, tag evaluated ONCE on entry,
  rethrown unless `Environment.isEqStrict`. `ThrowSignal` is deliberately NOT a
  `LispEvalException`, which is why `handler-case` lets it through.
- Compile path REUSES the cross-lambda machinery
  (`Jvm/WasmNlxCompiler.compileTagCatch`/`compileTagThrow`); the tag is snapshotted into a
  local BEFORE the region and the landing compares with `eq`.
- **Why the two exit kinds never collide.** JVM: a block id is a fresh `new Object()`, never
  `eq` to a Lisp tag. WASM: ids are i31 VALUES, so `(catch 3 ...)` WOULD have swallowed the
  exit of a block whose id is 3 — the payload SHAPE discriminates (block exit `(id . value)`,
  user throw `((tag) . value)`), and the landing pad `ref.test`s for the wrapper first.
- Gating: both join `usesEhForm` and widen the block-exit gate (`Ctx.blockExitTag` wasm /
  `Ctx.blockExitChannel` JVM); a program using neither is byte-identical. `--no-gc` rejects
  both.
- An unmatched `throw` = an error message (interpreter),
  `RuntimeException: THROW: no enclosing catch for the tag` (JVM, constant message, tag not
  printed), a trap on wasm-GC. In wasm async mode the tag snapshot is skipped, so a
  NON-CONSTANT catch tag inside an `async-defun` is re-evaluated on the unwind.

## `tagbody`/`go` + `prog`/`prog*`
- **A tag is a symbol OR an integer** (CLHS 5.3, compared with `eql` -- the ANSI suite's
  handler-case idiom aborts with `(go 10)` to a numeric label). Every tag table keys the
  two uniformly: the interpreter's `evalTagbody` labels map by `plainName` /
  `String.valueOf`, the backend `labelName` helpers by the same strings, so `go` and the
  label scan share one predicate. Pinned by `LispEvaluatorTest.tagbodyAcceptsIntegerTags`,
  `JvmLispCompilerTest.compileAndRunIntegerTagbodyTag`,
  `WasmLispCompilerIntegrationTest.integerTagbodyTag`, ci-spec `integer-tagbody-tag`.
- **Interpreter = dynamic `go`** (a superset of CL's lexical `go`): a thrown `GoSignal`
  re-entering at the label, so it **crosses function boundaries**.
- **Except in a statement's tail** (`LispEvaluator.evalTagbodyStatement`, 2026-09-19,
  `.todo/901`): `evalTagbody` walks each statement through `if`, `progn`, `let`, and the
  memoized expansions of `let*`/`when`/`unless`/`cond`, and a `(go L)` there whose tag is
  one of ITS labels answers the label's index instead of throwing -- the walk never enters
  another `tagbody`, so this is the innermost owner either way. A `let` binding a special
  undoes it in its `finally` before the index leaves, as before the throw did. Every other
  `go` (an argument position, an outer tag, a closure) still throws. The walk stands in for
  `evalConsClassifyingRawFailures` at each form it takes apart, so a malformed statement is
  still a `program-error`; any other shape goes to `eval`. The label table is a key array
  scanned from the end (the last duplicate wins, as the `HashMap` it replaced), not a map
  built per entry.
  - Why: a thrown `go` unwinds every Java frame between it and the `tagbody` (`evalCons` ->
    `evalIf` -> `eval` -> `evalLet` ...), and the JIT only turns that into a jump when all of
    them inline, which the recursive evaluator rarely allows. Measured (same day, loaded
    64-core box, whole-process wall clock incl. ~0.55 s start-up, before -> after): a Scheme
    named-`let` count-down, 300 x 3,000 steps, 2.65-3.07 -> 1.81-1.88 s, against 2.27-2.55 s
    for the same steps as non-tail calls -- a loop step now costs less than a call step; the
    CL `tagbody` count-down in `steps.lisp` (2 x 300 x 3,000 each way) 4.42-4.54 -> 3.59-3.90
    s. The Scheme tail-call groups: `.kb/scheme-frontend.md`.
  - `.todo/912` (`eval` as a loop) generalizes the same tail context; the walk is its
    special case with "jump" as the continuation, and folds into it.
  - Pinned by `LispEvaluatorTest.aTagbodyStatementAnswersATailGoInsteadOfThrowingIt`,
    `#aTailGoUndoesTheBindingsItLeavesAndReachesTheInnermostLabel` (SBCL prints the same),
    `#aMalformedTagbodyStatementIsStillAProgramError`.
- **Compilers = LEXICAL**: `go` becomes goto/br when its tag is in the SAME compiled function.
  `JvmTagbodyCompiler` (every label a `joinShape` join point at the tagbody's entry stack
  shape) + `JvmGoCompiler`; `WasmTagbodyCompiler` (dispatch loop + `br_table`, `i31` pc), which
  **rejects `await` inside**.
- **Lowered lambda crossing**: a `go` whose tag is established OUTSIDE its nested lambda goes
  through `CrossLambdaExitLowering`, plus one move — a block exit LEAVES its block, a `go`
  RE-ENTERS its tagbody at a label. The establishing `tagbody` becomes a re-entry loop of
  ORDINARY forms, so neither backend's compiler knows about it:

  ```lisp
  (let ((id (%nlx-tag)) (pc 0) (r nil))
    (tagbody retry (setq r (%nlx-catch id (tagbody (if (= pc 1) (go t1)) ... ITEMS)))
      (if r (progn (setq pc r) (go retry)))))
  ```

  The crossing `(go t1)` becomes `(%nlx-throw id 1)` — the payload is the label's **1-based
  re-entry index**, so normal completion yields nil and stops the loop. Only labels a crossing
  `go` targets get a dispatch entry; a tagbody without one is emitted verbatim. Keyword labels
  count. **Why at the AST level**: an in-backend fix would restructure every JVM tagbody into a
  runtime pc-dispatch loop.
- **Still unsupported on the compile path**: a `go` whose tag is not lexically visible at all
  — the loud `GO tag X has no lexically enclosing tagbody` stub.
- `prog`/`prog*` = `%block` + `let`/`let*` + `tagbody`, so `(return x)` exits the prog.

## Tests
`JvmLispCompilerTest.compileAndRunReturnInArgumentPosition`,
`.compileAndRunReturnFromInsideLambdaExitsOuterDefun`,
`.compileAndRunGoInsideLambdaReentersOuterTagbody`,
`.compileAndRunNestedNonLocalExitInACleanupDoesNotLoseTheOuterOne`;
`LispEvaluatorTest.returnInAHandlerBindHandlerExitsTheLEXICALNilBlock` (+ four neighbours),
`.unwindProtectCleanupCompletingItsOwnExitKeepsThePendingOne`; wasm
`returnFromExitsDefunAcrossLoop`, `goInsideLambdaReentersOuterTagbody`,
`nestedNonLocalExitInACleanupDoesNotLoseTheOuterOne`,
`catchThrowAndCrossLambdaExitDoNotCollide`; ci-spec `handler-case-in-argument-position`,
`handler-bind-return-lexical-block`, `cross-lambda-return-from`, `cross-lambda-go`,
`nested-exit-in-an-unwind-cleanup`, `catch-throw`.
