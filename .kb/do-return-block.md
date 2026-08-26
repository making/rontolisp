# `do`/`return` and the `%block` non-local exit boundary

`do` is a macro (`LispMacroExpander.expandDo`) expanding to a `let`/`while` loop with parallel-stepped vars (assigned through temporaries). `do`/`dolist`/`dotimes` wrap their expansion in the internal `%block` special form (`LispNames.BLOCK_INTERNAL`, a `CL_INTERNALS` symbol); `return` (`LispNames.RETURN`) is a non-local exit to the **nearest** enclosing `%block`.

Per backend:
- Interpreter throws `BlockReturnSignal` (stack-trace-free) at the block scope `evalBlock` established, which catches it (see the LEXICAL section below).
- JVM stores the value into a local then `goto`s the block exit (`JvmBlockCompiler`/`JvmReturnCompiler`, `Ctx.blockTargets`) — store-then-jump means the exit is reached with the operand stack the block was *entered* with (`BlockTarget.entryStack`) on every path, which the verifier requires (and which lets the `StackMapAugmenter` post-pass compute one consistent frame per merge point — [[stackmap-augmenter]]). A `return` mid-expression therefore **discards** the operands the abandoned expression had already evaluated (`JvmReturnCompiler.emitStackUnwind`, over `Ctx.stack` — see `.kb/error-handling.md`); before that it silently emitted a class the verifier rejects.
- WASM emits `block (result (ref null eq))` and `return` is a `br` at depth `Ctx.wasmCtrlDepth - marker` (`WasmBlockCompiler`/`WasmReturnCompiler`; `wasmCtrlDepth` is bumped only by `if` (+1) and `while` (+2)). A `br` discards the operands above the label's arity for free, which is why wasm never had the JVM's problem.

`return` works mid-expression on all four backends (`(list "seen" (if (= x 2) (return :done) x))`), pinned by `JvmLispCompilerTest.compileAndRunReturnInArgumentPosition` and the ci-spec `handler-case-in-argument-position` case. `member`/`assoc` are themselves expanded through `do`/`return` with an `(atom cursor)` end-test. The runtime `_eval` interpreters do not know `do`/`return`/`%block` (README).

**`block`/`return-from` (INTERPRETER) is LEXICAL, like the compile path**: ONE
mechanism serves every block. `runBlock` (`LispEvaluator`, behind
`evalBlock`/`evalNamedBlock`) runs the body in an `Environment` of its own and marks
it with the block's name (`Environment.installBlock`); **that scope object IS the
block's identity**, one per activation. `blockExit` resolves the exit's name up the
LEXICAL chain of the exit site (`Environment.findBlock` -- the same chain a `lambda`
closes over) and throws `BlockReturnSignal(targetScope, name, value)`, which only the
scope it names catches (`signal.target() == blockEnv`). The nil block is an ordinary
name: `%block`, `(block nil ...)`, plain `return` and `(return-from nil v)` all use
`NIL_BLOCK` (`"NIL"`), so the loop macros' implicit block and a user nil block are the
same construct, and named blocks in between are transparent to a plain `return` for
free (their scopes carry a different name) -- the transparency that lets cl-ppcre's
`collect-char-class` exit the function from inside a `loop` whose after-loop code must
NOT run. Two failure modes, both errors: a name NO enclosing scope establishes is a
`LispEvalException` at the exit site, and an exit to a block whose activation already
returned (the scope survives in a closure, nothing on the stack answers to it) escapes
to the top-level `eval`/`evalResolved` entry as "no enclosing block named X".

Reason it is not dynamic (name-keyed, nearest active frame -- what it was until
`.todo/407`): a `handler-bind` handler runs at the SIGNAL point, deep inside the
signalling function, so `(block nil (handler-bind ((condition (lambda (c) (return c))))
...))` -- rove's `signals`, verbatim -- was caught by whatever `loop`/`dolist`/`dotimes`
the signalling function happened to be running, which then returned the CONDITION as
its value. Named blocks were never affected, which is what pointed at the lookup.
A dynamic exit is still available and still spelled differently: `catch`/`throw`.
The interpreter-only DYNAMIC `go` (a tag established by the caller) is untouched --
`tagbody` keeps its own signal-and-re-enter machinery.

`evalDefun` wraps the (LambdaLists-expanded, rewrite SKIPPED via the 3-arg
`LambdaLists.expand(…, false)`) body in `(block <function-name> ...)` (setf-functions
use the place name); `expandDefmethod` (shared) wraps method bodies in
`(block <generic-name> ...)`; lambdas get NO block, so a `return-from` inside a lambda
exits the named function it was BUILT in, as in CL. Such a body is a single block form
(`LispEvaluator.soleBlockForm`), and `apply` then installs the block on the CALL's own
scope instead of a child of it -- that scope is fresh, private to the activation and
covers exactly the block's extent, so the commonest call still allocates one scope, not
two (measured: without it, a call-heavy interpreted benchmark lost ~4%).
`(loop named foo ...)` wraps the loop expansion in `(block foo ...)`
(`LispMacroExpander.LoopExpander`, all backends), so `(return-from foo v)` exits it
-- lite: the implicit `%block` stays inside, so plain `return` still exits the loop
(CL says a named loop has no nil block).

Pinned by `LispEvaluatorTest.returnInAHandlerBindHandlerExitsTheLEXICALNilBlock` and
its four neighbours (the named twin, the rove-shaped `signals`, the lexical-not-dynamic
direction, the escaped exit), the `compileAndRun` twins
(`JvmLispCompilerTest.compileAndRunReturnInAHandlerBindHandlerExitsTheLexicalNilBlock`,
wasm `returnInAHandlerBindHandlerExitsTheLexicalNilBlock`) and the ci-spec
`handler-bind-return-lexical-block` case.

**Named `block`/`return-from` on the COMPILE PATH (JVM + wasm-GC, LEXICAL)**: the
compilers implement named blocks as real goto/br targets keyed by name, within one
compiled function. Machinery:

- **`%fn-block` function boundary** (`LispNames.FN_BLOCK_INTERNAL`, a `CL_INTERNALS`
  symbol): `LambdaLists.wrapReturnFrom` — run from `expand()` (the lambda compilers'
  `toNative` path, block name nil, idempotent re-entry check) and from
  `desugarProgram`'s defun rebuild (block name = the defun's name; setf-functions use
  the PLACE name via `setfFunctionPlaceName`, mirroring `evalDefun`) — wraps a
  `return-from`-containing body in `(%fn-block name body...)`. The scan still stops at
  nested `lambda`/`defun` boundaries (`containsReturnFrom`). The interpreter passes
  `expand(..., false)` and keeps its native dynamic blocks.
- **Target resolution**: `JvmLispCompiler.BlockTarget` / `WasmLispCompiler.BlockMarker`
  carry `(name, catchesPlain, functionBoundary)`. Plain `return` targets the nearest
  `catchesPlain` block (`%block` or `(block nil ...)`), SKIPPING named blocks — the
  goto/br analog of the interpreter's signal transparency. `(return-from name v)`
  (`JvmReturnFromCompiler`/`WasmReturnFromCompiler`) targets the nearest block whose
  name matches — a user `(block name ...)` (`compileNamed`) or the `%fn-block`
  (`compileFnBlock`) — falling back to the nearest `functionBoundary` when no name
  matches, so an unmatched `return-from` exits the current function. `(return-from nil
  v)` compiles as plain `return`. `(block nil ...)` compiles exactly like `%block`.
  `LispMacroExpander.blockName` mirrors the interpreter's designator handling
  (nil/`"nil"` → the nil block).
- **Unwind interplay**: on the JVM, `JvmReturnCompiler.emitExit` is the shared exit
  sequence generalized to any target depth (escaped `unwind-protect` cleanups inline
  with hole recording, `handler-case` spill restore, operand-stack unwind — the
  comparisons use the target's 1-based block-stack depth instead of the stack size).
  On WASM, plain `return` keeps the pre-built trampoline cascade (its continuation now
  computed against the nearest `catchesPlain` marker), while `return-from` INLINES the
  escaped scopes' cleanups at the exit site and brs straight to the target — the same
  strategy and lite limit as `go` (a throw from an inlined cleanup can re-enter its own
  handler).
- **Cross-lambda `return-from` AND plain `return` = a real non-local exit** (JVM +
  wasm-GC, EH-based; pinned by `compileAndRunReturnFromInsideLambdaExitsOuterDefun` / wasm
  `returnFromExitsDefunAcrossLoop` and the `cross-lambda-return-from` ci-spec case): a
  `return-from` inside a lambda whose name matches a block in the lexically enclosing
  function exits the OUTER defun/block, matching the interpreter and CL. The lexical
  goto/br fast path stays for a same-function exit; only one that actually crosses
  a lambda boundary pays the EH cost. Mechanics — the shared, compile-path-only
  `compiler/CrossLambdaExitLowering` (run before `desugarProgram`) detects an exit
  whose target block is established outside the nested lambda it sits in and rewrites the
  establishing block to `(let ((id (%nlx-tag))) (%nlx-catch id BODY))` with the crossing
  `(return-from name v)` rewritten to `(%nlx-throw id v)`. Covered boundaries include
  `flet`/`labels` definition bodies (counted one lambda level deeper, since they expand
  into lambdas); covered exits include a bare `(return [v])` and `(return-from nil ...)`,
  whose establishing point is the nearest NIL-BLOCK scope — a loop macro
  (`loop`/`do`/`do*`/`dotimes`/`dolist`/`prog`/`prog*`), the internal `%block`, or a user
  `(block nil ...)` — with named blocks in between transparent, mirroring the runtime
  signal transparency (cl-postgres' `message-case` does `(return)` out of a `loop` from
  inside a `labels` function). The `id` is a **dynamic block-instance id** — a genuine
  lexical the existing closure machinery (`FreeVarAnalyzer`) captures into the lambda,
  minted fresh per block activation (`%nlx-tag`), so recursion targets the right frame and
  an exit after the block returned surfaces as an error. `%nlx-throw`/`%nlx-catch` unwind
  the real stack: JVM via a plain `RuntimeException` + the `_nleTl`
  `{throwable,id,value,previous}` channel (`JvmNlxCompiler`), wasm via a dedicated
  `$block-exit` tag carrying an
  `(id . value)` cons (`WasmNlxCompiler`, tag 1, gated). **On wasm the id is an i31 VALUE**
  (the next integer from the `NLX_ID_CTR` linear-memory cell, compared by `ref.eq` which
  is value equality for i31), and the catch snapshots it into a dedicated local at region
  entry — never a fresh GC struct compared by identity: the original
  `struct.new $cell` identity scheme broke at cl-ppcre scale in a shape-dependent,
  engine-divergent way (wasmtime 46/47 AND V8 flipped behavior under semantically inert
  code insertions; the forensic record lives in `.todo/115`). The JVM keeps plain object
  identity (`new Object()`-style tags), which is sound there. Intervening
  `unwind-protect` cleanups run on the way out (JVM native
  unwind; wasm `catch_all_ref`) — and **the JVM channel is a STACK, not a slot, precisely
  because of them**: a cleanup runs while the exit that triggered it is still travelling,
  and a cleanup that completes a non-local exit OF ITS OWN (a nested `catch`/`throw` pair,
  a `return-from` out of an inner block — cl-postgres-client's
  `(unless settled (ignore-errors (execute client "ROLLBACK")))`) used to clear the
  channel on its way out. The outer exit then found nothing at its own landing pad, was
  rethrown past its block, and the first enclosing `handler-case`/`%hb-guard` synthesized
  it into a message-less `simple-error` — surfacing as rove's "Raise an error while
  testing." `%nlx-throw` pushes `previous` (read after the tag/value forms have run, so an
  exit those complete is already popped) and a matching `%nlx-catch`/`catch` pops back to
  it instead of nulling. wasm never had the bug: the id and value ride the tag payload, so
  there is no shared channel to consume. Pinned by
  `compileAndRunNestedNonLocalExitInACleanupDoesNotLoseTheOuterOne` (JVM),
  `nestedNonLocalExitInACleanupDoesNotLoseTheOuterOne` (wasm),
  `unwindProtectCleanupCompletingItsOwnExitKeepsThePendingOne` (interpreter) and the
  `nested-exit-in-an-unwind-cleanup` ci-spec case. `handler-case` does NOT intercept it:
  the JVM handler
  rethrows a pending `_nleTl` before dispatching, and wasm uses a distinct tag with a
  block-exit passthrough that restores the handler depth (`JvmHandlerCaseCompiler` /
  `WasmHandlerCaseCompiler`, both gated on the cross-lambda-exit flag). **EH-mode
  trigger:** a program with a cross-lambda exit compiles in EH mode, exactly like
  `handler-case`; a program without one is byte-identical. `--no-gc` keeps the old
  name-dropping `expandBlock` lowering and has no `return-from` at all (it never ran
  `desugarProgram`), so this does not apply there; the interpreter was already correct and
  is untouched.
- **Cross-lambda `go`** rides the same machinery with one extra move; see the
  `tagbody`/`go` section below. Still unsupported on the compile path: a `go` whose tag
  is not LEXICALLY visible at all (the interpreter's dynamic `go` into a *caller's*
  tagbody).

Needed by cl-utilities' `rotate-byte`/`read-delimited` (function-scoped early returns,
now exact) and cl-ppcre (`ClPpcreE2eTest`, all four backends: `(block scan ...)` in the
generated scanner closures, `collect-char-class` returning across a `loop`).

## `catch`/`throw` (dynamic, tag-keyed exits)

`(catch tag body...)` / `(throw tag result)` are the DYNAMIC counterpart of
`block`/`return-from`: the target is an ordinary runtime value compared with `eq`, not a
lexically visible name, so the thrower only has to run inside the catcher's dynamic
extent. Both are `cl` SPECIAL FORMS (`LispNames.CATCH`/`THROW`,
`PackageRegistry.CL_SPECIAL_FORMS`). `LispNames.CATCH` is ONE constant serving two
operators: bare `catch` is this special form, `rontolisp:catch`
(`LispNames.CATCH_QUALIFIED`) is the unrelated future combinator -- the package
qualification, not the constant, tells them apart.

- **Interpreter**: `evalCatch`/`evalThrow`. `throw` raises a `ThrowSignal(tag, value)`;
  the catch evaluates its tag ONCE on entry and rethrows a signal whose tag is not
  `Environment.isEqStrict` to it, so the innermost matching catcher wins. `ThrowSignal` is
  deliberately NOT a `LispEvalException`, which is exactly why `handler-case` (which
  catches only that) lets it through; `unwind-protect` is `try`/`finally`, so cleanups run.
  An unmatched throw is turned into an ordinary `LispEvalException`
  ("no enclosing catch for tag X") at the top-level `eval`/`evalResolved` entry, the same
  place a stray `BlockReturnSignal` is.
- **COMPILE PATH (JVM + wasm-GC)**: they REUSE the cross-lambda-exit machinery above --
  `JvmNlxCompiler.compileTagCatch`/`compileTagThrow` and
  `WasmNlxCompiler.compileTagCatch`/`compileTagThrow`, i.e. the same `_nleTl` channel /
  `$block-exit` tag, hence the same unwind-protect interplay and the same `handler-case`
  passthrough. Two differences from `%nlx-catch`: the tag is an arbitrary expression
  evaluated once BEFORE the protected region (a snapshot local -- CL evaluates it on
  entry, not on the unwind), and the landing compares with `eq`
  (`WasmEmitHelper.emitEqComparison`; on the JVM the compare is built as an `(eq a b)`
  form over two pseudo-locals so the nil-safe `JvmEqGeneralCompiler` is reused).
- **Why the two exit kinds never collide.** On the JVM a block-instance id is a fresh
  `new Object()`: never `eq` to a Lisp tag, never identical to one, so both landing pads
  reject the other's payload for free. On wasm the ids are i31 VALUES and `ref.eq` on an
  i31 is value equality, so a `(catch 3 ...)` WOULD have swallowed the exit of a block
  whose minted id is 3 (verified: it did). The payload shape is therefore the
  discriminator -- a block exit throws `(id . value)`, a user throw
  `((tag) . value)` with a wrapper cons -- and the user landing pad `ref.test`s for that
  wrapper before comparing. Pinned by `catchThrowAndCrossLambdaExitDoNotCollide` (wasm +
  JVM) and the `catch-throw` ci-spec case.
- **EH-mode / tag gating**: `catch`/`throw` join the `usesEhForm` list and widen the
  block-exit gate, renamed to what it now means -- `Ctx.blockExitTag` (wasm) /
  `Ctx.blockExitChannel` (JVM), true when EITHER a cross-lambda exit is lowered OR the
  program uses `catch`/`throw`. So a program using them compiles in EH mode like every
  other EH form, and a program using neither stays byte-identical.
- **Limits**: `--no-gc` rejects both with a compile error (its contract is a zero-flag MVP
  module). An unmatched `throw` surfaces per backend: an error message on the interpreter,
  `RuntimeException: THROW: no enclosing catch for the tag` (a compile-time constant
  message -- the tag is not printed, so a caught throw costs nothing) on the JVM, a trap
  on wasm-GC, exactly like an uncaught `error` there. In wasm state-machine (async) mode
  the tag snapshot is skipped -- a resume re-enters past the region's entry code -- so a
  NON-CONSTANT catch tag inside an `async-defun` is re-evaluated on the unwind; the normal
  quoted-symbol tag is unaffected. The runtime `_eval` interpreters do not know
  `catch`/`throw`, like the rest of this file's forms.

## `tagbody`/`go` + `prog`/`prog*`

`tagbody`/`go` and `prog`/`prog*` work on all three backends (interpreter, JVM,
wasm-GC).

- **Interpreter = dynamic `go`** (a superset of CL, which makes `go` lexical): `go` throws a `GoSignal` exception; `evalTagbody`
  catches it and re-enters at the target label via label-indexed re-entry. Because it
  is a thrown signal, a dynamic `go` **crosses function boundaries** (the target need
  not be lexically enclosing).
- **Compilers = LEXICAL, with a lowered lambda crossing**: a `go` compiles to a
  goto/br when its tag belongs to a `tagbody` in the SAME compiled function. A `go`
  whose tag is established OUTSIDE the nested lambda it sits in (a `handler-bind`
  handler resuming its loop -- quri's `:lenient` percent-decoding) is lowered by
  `compiler/CrossLambdaExitLowering` exactly like the cross-lambda `return-from` above,
  plus one move: a block exit LEAVES its block, but a `go` RE-ENTERS its tagbody at a
  label and keeps running. The establishing `tagbody` is therefore rewritten into a
  re-entry loop **built from ordinary forms**, so NEITHER backend's `tagbody`/`go`
  compiler knows about any of this:

  ```lisp
  (let ((id (%nlx-tag)) (pc 0) (r nil))
    (tagbody
     retry
       (setq r (%nlx-catch id (tagbody (if (= pc 1) (go t1)) ... ORIGINAL-ITEMS)))
       (if r (progn (setq pc r) (go retry)))))
  ```

  The crossing `(go t1)` becomes `(%nlx-throw id 1)` -- the payload is the target
  label's **1-based re-entry index**, not the tag name, and the `id` is the same
  per-activation `%nlx-tag` lexical, so a recursive function's inner activation cannot
  catch an outer one's `go`. Normal completion of the inner tagbody yields nil (a
  tagbody's value), which is what stops the loop: indices start at 1 precisely so nil
  is unambiguous. Only labels an actual crossing `go` targets get a dispatch entry, and
  a tagbody with no crossing `go` is emitted verbatim (byte-identical). Keyword labels
  count -- quri's `with-array-parsing` names its end-of-input segment `:eof`.
  `prog`/`prog*` establish tags the same way (their body IS a tagbody body): the
  re-entry loop is spliced in as their single body item, leaving the `%block` a plain
  `(return)` exits in place. Same EH gating as the `return-from` crossing (it sets the
  same `crossLambda.used()` flag).
  Pinned by `JvmLispCompilerTest.compileAndRunGoInsideLambdaReentersOuterTagbody`, the
  wasm `goInsideLambdaReentersOuterTagbody`, and the `cross-lambda-go` ci-spec case.
  **Reason the lowering lives at the AST level**: restructuring the JVM `tagbody` into a
  runtime pc-dispatch loop (what an in-backend fix needs; wasm already is one) would
  change every tagbody's emitted code, including the vast majority with no crossing
  `go`. Revisit only if a shape appears that the AST rewrite cannot express.
  Still unsupported: a `go` whose tag is not lexically visible at all -- the
  interpreter's dynamic `go` into a *caller's* tagbody -- which keeps the loud
  `GO tag X has no lexically enclosing tagbody` stub.
  - **JVM**: `JvmTagbodyCompiler` lowers to goto/patch, with every label emitted as a
    `joinShape` join point at the tagbody's entry stack shape. `JvmGoCompiler` performs
    the escaped-cleanup/spill unwind (inlining escaped `unwind-protect` cleanups,
    restoring `handler-case` spills, operand-stack unwind) mirroring `return`
    (`JvmReturnCompiler`).
  - **WASM**: `WasmTagbodyCompiler` emits a dispatch loop plus a `br_table` over the
    segment blocks, using an `i31`-boxed pc. `go` inlines escaped `unwind-protect`
    cleanups at the branch site (same strategy/limit as `return-from` above). It
    **rejects `await` inside** a tagbody.
- **`prog`/`prog*`** = `%block` + `let`/`let*` + `tagbody`, so a user `(return x)`
  inside a `prog` exits the prog (via the `%block` boundary).
