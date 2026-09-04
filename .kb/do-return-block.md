# `do`/`return` and the `%block` non-local exit boundary

`do` is a macro (`LispMacroExpander.expandDo`) expanding to a `let`/`while` loop with
parallel-stepped vars (assigned through temporaries). `do`/`dolist`/`dotimes` wrap their
expansion in the internal `%block` (`LispNames.BLOCK_INTERNAL`, a `CL_INTERNALS` symbol);
`return` (`LispNames.RETURN`) exits to the **nearest** enclosing `%block`. `member`/`assoc`
are themselves expanded through `do`/`return` with an `(atom cursor)` end-test. The runtime
`_eval` interpreters know none of this file's forms.

Plain `return` per backend:

- **Interpreter**: throws `BlockReturnSignal` (stack-trace-free) at the scope `evalBlock`
  established.
- **JVM**: store the value into a local, then `goto` the block exit
  (`JvmBlockCompiler`/`JvmReturnCompiler`, `Ctx.blockTargets`). Store-then-jump reaches the
  exit with the operand stack the block was *entered* with (`BlockTarget.entryStack`) on
  every path -- the verifier requires it, and it lets `StackMapAugmenter` compute one frame
  per merge point ([[stackmap-augmenter]]). A `return` mid-expression therefore **discards**
  the abandoned expression's already-evaluated operands
  (`JvmReturnCompiler.emitStackUnwind` over `Ctx.stack`; `.kb/error-handling.md`); without
  that it emitted a class the verifier rejects.
- **WASM**: `block (result (ref null eq))`; `return` is a `br` at depth
  `Ctx.wasmCtrlDepth - marker` (`WasmBlockCompiler`/`WasmReturnCompiler`; `wasmCtrlDepth` is
  bumped only by `if` (+1) and `while` (+2)). `br` discards operands above the label's arity
  for free, so wasm never had the JVM's problem.

`return` works mid-expression on all four; pinned by
`JvmLispCompilerTest.compileAndRunReturnInArgumentPosition` and ci-spec
`handler-case-in-argument-position`.

## `block`/`return-from` on the INTERPRETER is LEXICAL, like the compile path

ONE mechanism serves every block.

- `runBlock` (behind `evalBlock`/`evalNamedBlock`) runs the body in its own `Environment`
  marked with the block name (`Environment.installBlock`). **That scope object IS the
  block's identity**, one per activation.
- `blockExit` resolves the name up the LEXICAL chain of the exit site
  (`Environment.findBlock` -- the chain a `lambda` closes over) and throws
  `BlockReturnSignal(targetScope, name, value)`, caught only by the scope it names
  (`signal.target() == blockEnv`).
- The nil block is an ordinary name: `%block`, `(block nil ...)`, plain `return` and
  `(return-from nil v)` all use `NIL_BLOCK` (`"NIL"`), so a loop macro's implicit block and a
  user nil block are one construct, and named blocks in between are transparent to a plain
  `return` for free (their scopes carry a different name).
- Two failure modes, both errors: a name no enclosing scope establishes is a
  `LispEvalException` at the exit site; an exit to a block whose activation already returned
  escapes to the top-level `eval`/`evalResolved` entry as "no enclosing block named X".
- **Not dynamic on purpose**: a `handler-bind` handler runs at the SIGNAL point, so a
  name-keyed nearest-active-frame lookup let whatever `loop`/`dolist`/`dotimes` the
  signalling function was running catch `(block nil (handler-bind ((condition (lambda (c)
  (return c)))) ...))` -- rove's `signals` -- and return the CONDITION as its value. For a
  dynamic exit use `catch`/`throw`. The interpreter-only DYNAMIC `go` is untouched.

Block installation: `evalDefun` wraps the (LambdaLists-expanded, rewrite SKIPPED via the
3-arg `LambdaLists.expand(..., false)`) body in `(block <function-name> ...)` (setf-functions
use the place name); `expandDefmethod` (shared) wraps method bodies in
`(block <generic-name> ...)`; **lambdas get NO block**, so a `return-from` inside a lambda
exits the named function it was BUILT in, as in CL. Such a body is a single block form
(`LispEvaluator.soleBlockForm`), so `apply` installs the block on the CALL's own scope rather
than a child (one scope per call, not two; ~4% on a call-heavy benchmark).
`(loop named foo ...)` wraps the expansion in `(block foo ...)`
(`LispMacroExpander.LoopExpander`, all backends) with the implicit `%block` still inside, so
plain `return` exits the loop (CL: a named loop has no nil block).

Pinned by `LispEvaluatorTest.returnInAHandlerBindHandlerExitsTheLEXICALNilBlock` + four
neighbours (named twin, rove-shaped `signals`, lexical-not-dynamic, escaped exit), the
`compileAndRun` twins
(`JvmLispCompilerTest.compileAndRunReturnInAHandlerBindHandlerExitsTheLexicalNilBlock`, wasm
`returnInAHandlerBindHandlerExitsTheLexicalNilBlock`) and ci-spec
`handler-bind-return-lexical-block`.

## Named `block`/`return-from` on the COMPILE PATH (JVM + wasm-GC, LEXICAL)

Real goto/br targets keyed by name, within one compiled function.

- **`%fn-block`** (`LispNames.FN_BLOCK_INTERNAL`, `CL_INTERNALS`): `LambdaLists.wrapReturnFrom`
  -- from `expand()` (the lambda compilers' `toNative` path, block name nil, idempotent
  re-entry check) and from `desugarProgram`'s defun rebuild (name = the defun's;
  setf-functions via `setfFunctionPlaceName`) -- wraps a `return-from`-containing body in
  `(%fn-block name body...)`. The scan stops at nested `lambda`/`defun`
  (`containsReturnFrom`). The interpreter passes `expand(..., false)`.
- **Targets**: `JvmLispCompiler.BlockTarget` / `WasmLispCompiler.BlockMarker` carry
  `(name, catchesPlain, functionBoundary)`. Plain `return` takes the nearest `catchesPlain`
  block, SKIPPING named blocks (the goto/br analog of signal transparency).
  `(return-from name v)` (`JvmReturnFromCompiler`/`WasmReturnFromCompiler`) takes the nearest
  matching name -- a user `(block name ...)` (`compileNamed`) or the `%fn-block`
  (`compileFnBlock`) -- falling back to the nearest `functionBoundary`, so an unmatched
  `return-from` exits the current function. `(return-from nil v)` compiles as plain `return`;
  `(block nil ...)` compiles exactly like `%block`. `LispMacroExpander.blockName` mirrors the
  interpreter's designator handling (nil/`"nil"` -> the nil block).
- **Unwind interplay**: JVM `JvmReturnCompiler.emitExit` is the shared exit sequence
  generalized to any target depth (escaped `unwind-protect` cleanups inlined with hole
  recording, `handler-case` spill restore, operand-stack unwind; comparisons use the target's
  1-based block-stack depth, not the stack size). WASM: plain `return` keeps the pre-built
  trampoline cascade (continuation computed against the nearest `catchesPlain` marker), while
  `return-from` INLINES escaped cleanups at the exit site and brs straight to the target --
  same strategy and lite limit as `go` (a throw from an inlined cleanup can re-enter its own
  handler).

### Cross-lambda `return-from` and plain `return` = a real non-local exit (EH-based)

`compiler/CrossLambdaExitLowering` (compile-path only, run before `desugarProgram`) detects
an exit whose target block is established outside the nested lambda it sits in and rewrites
the establishing block to `(let ((id (%nlx-tag))) (%nlx-catch id BODY))`, the crossing exit
to `(%nlx-throw id v)`. The lexical goto/br fast path stays for a same-function exit.

- Covered boundaries include `flet`/`labels` definition bodies (one lambda level deeper,
  since they expand into lambdas).
- Covered exits include a bare `(return [v])` / `(return-from nil ...)`, whose establishing
  point is the nearest NIL-BLOCK scope -- a loop macro
  (`loop`/`do`/`do*`/`dotimes`/`dolist`/`prog`/`prog*`), `%block`, or a user
  `(block nil ...)` -- with named blocks in between transparent.
- The `id` is a **dynamic block-instance id**: a genuine lexical `FreeVarAnalyzer` captures
  into the lambda, minted fresh per activation (`%nlx-tag`), so recursion targets the right
  frame and an exit after the block returned is an error.
- Transport: JVM a plain `RuntimeException` + the `_nleTl` `{throwable,id,value,previous}`
  channel (`JvmNlxCompiler`); wasm a dedicated `$block-exit` tag carrying an `(id . value)`
  cons (`WasmNlxCompiler`, tag 1, gated).
- **On wasm the id is an i31 VALUE** (next integer from the `NLX_ID_CTR` linear-memory cell,
  compared by `ref.eq` = value equality for i31), snapshotted into a dedicated local at
  region entry. **Never a fresh GC struct compared by identity**: the original
  `struct.new $cell` scheme broke at cl-ppcre scale, shape-dependently and
  engine-divergently (wasmtime 46/47 and V8 both flipped behavior under semantically inert
  code insertions). The JVM keeps object identity, which is sound there.
- Intervening `unwind-protect` cleanups run on the way out (JVM native unwind, wasm
  `catch_all_ref`). **The JVM channel is a STACK, not a slot, because of them**: a cleanup
  completing a non-local exit OF ITS OWN used to clear the channel, so the outer exit found
  nothing at its landing pad, was rethrown past its block, and the first enclosing
  `handler-case`/`%hb-guard` turned it into a message-less `simple-error`. `%nlx-throw`
  pushes `previous` (read after the tag/value forms have run, so an exit those complete is
  already popped) and `%nlx-catch`/`catch` pops back to it instead of nulling. wasm never had
  the bug (id and value ride the tag payload). Pinned by
  `compileAndRunNestedNonLocalExitInACleanupDoesNotLoseTheOuterOne` (JVM),
  `nestedNonLocalExitInACleanupDoesNotLoseTheOuterOne` (wasm),
  `unwindProtectCleanupCompletingItsOwnExitKeepsThePendingOne` (interpreter), ci-spec
  `nested-exit-in-an-unwind-cleanup`.
- **`handler-case` does NOT intercept it**: the JVM handler rethrows a pending `_nleTl`
  before dispatching; wasm uses a distinct tag with a block-exit passthrough restoring the
  handler depth (`JvmHandlerCaseCompiler` / `WasmHandlerCaseCompiler`, gated on the
  cross-lambda-exit flag).
- **EH-mode trigger**: a program with a cross-lambda exit compiles in EH mode like
  `handler-case`; one without is byte-identical. `--no-gc` keeps the old name-dropping
  `expandBlock` lowering and has no `return-from` at all (it never ran `desugarProgram`).

Pinned by `compileAndRunReturnFromInsideLambdaExitsOuterDefun` / wasm
`returnFromExitsDefunAcrossLoop` and ci-spec `cross-lambda-return-from`. Consumers:
cl-utilities' `rotate-byte`/`read-delimited`, cl-ppcre (`ClPpcreE2eTest`, four backends).

## `catch`/`throw` (dynamic, tag-keyed)

Target is a runtime value compared with `eq`, so the thrower only has to run inside the
catcher's dynamic extent. Both are `cl` SPECIAL FORMS (`LispNames.CATCH`/`THROW`,
`PackageRegistry.CL_SPECIAL_FORMS`). **`LispNames.CATCH` is ONE constant serving two
operators**: bare `catch` is this special form, `rontolisp:catch`
(`LispNames.CATCH_QUALIFIED`) is the unrelated future combinator -- the package
qualification, not the constant, tells them apart.

- **Interpreter**: `evalCatch`/`evalThrow`. `throw` raises `ThrowSignal(tag, value)`; the
  catch evaluates its tag ONCE on entry and rethrows a signal whose tag is not
  `Environment.isEqStrict` to it, so the innermost matching catcher wins. `ThrowSignal` is
  deliberately NOT a `LispEvalException`, which is why `handler-case` lets it through;
  `unwind-protect` is `try`/`finally`, so cleanups run. An unmatched throw becomes an
  ordinary `LispEvalException` ("no enclosing catch for tag X") at the top-level entry.
- **COMPILE PATH**: REUSES the cross-lambda machinery --
  `Jvm/WasmNlxCompiler.compileTagCatch`/`compileTagThrow`, same `_nleTl` channel /
  `$block-exit` tag, same unwind-protect interplay and `handler-case` passthrough. Two
  differences from `%nlx-catch`: the tag is an arbitrary expression evaluated once BEFORE the
  protected region (a snapshot local -- CL evaluates it on entry, not on the unwind), and the
  landing compares with `eq` (`WasmEmitHelper.emitEqComparison`; on the JVM built as an
  `(eq a b)` form over two pseudo-locals so nil-safe `JvmEqGeneralCompiler` is reused).
- **Why the two exit kinds never collide.** JVM: a block-instance id is a fresh
  `new Object()`, never `eq` to a Lisp tag. WASM: ids are i31 VALUES and `ref.eq` is value
  equality, so `(catch 3 ...)` WOULD have swallowed the exit of a block whose minted id is 3
  (verified). The payload SHAPE is the discriminator -- block exit `(id . value)`, user throw
  `((tag) . value)` with a wrapper cons -- and the user landing pad `ref.test`s for the
  wrapper before comparing. Pinned by `catchThrowAndCrossLambdaExitDoNotCollide` (wasm + JVM)
  and ci-spec `catch-throw`.
- **Gating**: they join the `usesEhForm` list and widen the block-exit gate, renamed
  `Ctx.blockExitTag` (wasm) / `Ctx.blockExitChannel` (JVM) -- true when EITHER a cross-lambda
  exit is lowered OR the program uses `catch`/`throw`. A program using neither is
  byte-identical.
- **Limits**: `--no-gc` rejects both with a compile error. An unmatched `throw`: an error
  message on the interpreter, `RuntimeException: THROW: no enclosing catch for the tag` (a
  compile-time constant message -- the tag is not printed, so a caught throw costs nothing)
  on the JVM, a trap on wasm-GC. In wasm state-machine (async) mode the tag snapshot is
  skipped (a resume re-enters past the region's entry code), so a NON-CONSTANT catch tag
  inside an `async-defun` is re-evaluated on the unwind; a quoted-symbol tag is unaffected.

## `tagbody`/`go` + `prog`/`prog*`

Interpreter, JVM and wasm-GC.

- **Interpreter = dynamic `go`** (a superset of CL's lexical `go`): `go` throws a `GoSignal`;
  `evalTagbody` catches it and re-enters at the target label via label-indexed re-entry.
  Being a thrown signal, it **crosses function boundaries**.
- **Compilers = LEXICAL, plus a lowered lambda crossing**: a `go` compiles to goto/br when
  its tag belongs to a `tagbody` in the SAME compiled function. A `go` whose tag is
  established OUTSIDE the nested lambda it sits in (a `handler-bind` handler resuming its
  loop -- quri's `:lenient` percent-decoding) goes through `CrossLambdaExitLowering` like a
  cross-lambda `return-from`, plus one move: a block exit LEAVES its block, a `go` RE-ENTERS
  its tagbody at a label. The establishing `tagbody` becomes a re-entry loop **built from
  ordinary forms**, so neither backend's `tagbody`/`go` compiler knows about any of it:

  ```lisp
  (let ((id (%nlx-tag)) (pc 0) (r nil))
    (tagbody
     retry
       (setq r (%nlx-catch id (tagbody (if (= pc 1) (go t1)) ... ORIGINAL-ITEMS)))
       (if r (progn (setq pc r) (go retry)))))
  ```

  The crossing `(go t1)` becomes `(%nlx-throw id 1)`: the payload is the label's **1-based
  re-entry index**, not the tag name, and `id` is the same per-activation `%nlx-tag` lexical,
  so a recursive function's inner activation cannot catch an outer one's `go`. Normal
  completion of the inner tagbody yields nil, which stops the loop -- indices start at 1
  precisely so nil is unambiguous. Only labels an actual crossing `go` targets get a dispatch
  entry; a tagbody with no crossing `go` is emitted verbatim (byte-identical). Keyword labels
  count (quri's `with-array-parsing` names its end-of-input segment `:eof`). `prog`/`prog*`
  establish tags the same way (their body IS a tagbody body): the re-entry loop is spliced in
  as their single body item, leaving the `%block` a plain `(return)` exits in place. Same EH
  gating as the `return-from` crossing (same `crossLambda.used()` flag). Pinned by
  `JvmLispCompilerTest.compileAndRunGoInsideLambdaReentersOuterTagbody`, wasm
  `goInsideLambdaReentersOuterTagbody`, ci-spec `cross-lambda-go`.
  **Why at the AST level**: restructuring the JVM `tagbody` into a runtime pc-dispatch loop
  (what an in-backend fix needs; wasm already is one) would change every tagbody's emitted
  code, including the vast majority with no crossing `go`. Revisit only for a shape the AST
  rewrite cannot express.
  **Still unsupported on the compile path**: a `go` whose tag is not lexically visible at all
  (the interpreter's dynamic `go` into a *caller's* tagbody), which keeps the loud
  `GO tag X has no lexically enclosing tagbody` stub.
  - **JVM**: `JvmTagbodyCompiler` lowers to goto/patch, every label a `joinShape` join point
    at the tagbody's entry stack shape. `JvmGoCompiler` does the escaped-cleanup/spill
    unwind, mirroring `JvmReturnCompiler`.
  - **WASM**: `WasmTagbodyCompiler` emits a dispatch loop plus a `br_table` over the segment
    blocks with an `i31`-boxed pc. `go` inlines escaped cleanups at the branch site (same
    strategy/limit as `return-from`). It **rejects `await` inside** a tagbody.
- **`prog`/`prog*`** = `%block` + `let`/`let*` + `tagbody`, so a user `(return x)` inside a
  `prog` exits the prog.
