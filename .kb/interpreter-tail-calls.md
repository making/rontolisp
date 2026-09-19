# The interpreter's `eval` is a loop: a tail call replaces the frame

**Invariant: `LispEvaluator.evalCons` is a LOOP, and a form in tail position of the current
one -- the arm an `if` takes, the last form of a `progn`, a `let` body or a block, a
macro's expansion, the body of a `LispLambda` a call applies -- REPLACES the current form
and environment instead of recursing in Java.** So a chain of tail calls runs in constant
Java stack on the default run path (`.kb/default-run-path.md`): through a procedure value
(`(funcall self self ..)`, which is every Scheme call through an argument or a session
definition), `apply` of a closure, mutual `defun`s, a `labels` function, a lambda head,
and through every tail-transparent form in between. No bounce and no allocation per call
-- the trampoline (`.kb/scheme-frontend.md`, "Not a trampoline") was measured at 16x per
bounced call and rejected; this shape is FASTER than the recursion it replaced, because a
Lisp call enters fewer Java frames. Landed 2026-09-19 (`.todo/912`).

## What the frame carries (locals of `evalCons`, no allocation)

- `owner` -- the exit target of every block the frame entered: the FIRST block's scope, and
  every later block entered in tail position (`block`, `%block`, a defun body's block)
  records the same one (`Environment.installBlock(name, owner)`, `findBlock` answers the
  owner, `BlockReturnSignal.target` IS the owner). Each of those blocks is the frame's own
  continuation, so a `return-from` aimed at any of them ends the frame with the exit's
  value -- which is exactly what the recursive version computed through its chain of
  `runBlockIn` catches, since a value returned to a tail position is the value of the
  frame. A block that runs in a frame of its own (`apply`'s, `evalDoSymbols`'s) is its own
  owner, as before. The old envs are garbage as soon as nothing captures them: 5,000,000
  activations of a defun keep ONE scope alive, not a list.
- `inBody` -- whether the frame entered a function body: `functionBodyDepth` (what tells a
  macro expansion whether its call site is top level, `expandUserMacro`) is raised once per
  frame however many bodies tail calls replace, and lowered in the frame's `finally` -- so
  the REPL's `controlState()`/`restore()` after a `StackOverflowError`
  (`.kb/interpreter-stack.md`) has the same stacks to put back.
- `funcallSeam` -- whether a `(funcall closure ..)` or `(apply closure ..)` was absorbed:
  the frame's `catch (LispEvalException)` then runs `withHandlerBindHandlersRun`, the
  signal-point seam the `funcall`/`apply` built-ins give what the closure raises. Wider
  than the old per-call catch and equivalent: once the closure's body has replaced the
  frame, everything the frame evaluates afterwards is inside that call's dynamic extent.
  The handlers run inside `apply` of `%run-handlers`, which raises the depth itself, so
  the order of the `finally` and the handler run is unobservable.
- `labels` (a parameter) -- non-null when the frame is a tagbody STATEMENT: a `(go L)`
  reached in tail context whose tag is one of the labels answers the label's jump token
  (`TagbodyLabels.jump`, an integer object private to the activation, told apart by
  identity) instead of throwing, and `evalTagbody` reads the index back
  (`jumpIndex`). This is `.todo/901`'s walk (`.kb/do-return-block.md`) generalized: the
  walk covered `if`/`progn`/`let`/`let*`/`when`/`unless`/`cond`; the loop covers every tail
  context, a called function's body included -- the same destination the thrown
  `GoSignal` reached, without the throw. A special `let` in the statement's tail still
  answers the token AFTER its `finally` (`evalLetIn` runs the last form through the loop
  with the labels).

## What keeps its frame, by construction

A form whose frame must outlive its value is a method of its own that evaluates its parts
through `eval` and returns: `handler-case`, `%hb-guard`, `unwind-protect`, `catch`,
`progv`, `multiple-value-prog1` (a `let` over the first form), a `let` binding a special
or `*package*` (`lexicalLet` answers null; `evalLetIn` keeps the restore), a lambda with a
special parameter (`lexicalLambdaScope` answers null; `apply` pops the dynamic binding),
every built-in call (`apply`'s seam), `tagbody` itself (its statements are frames of the
tagbody's loop), `while`, the definers. Adding a special form: nothing to do, unless it is
tail-transparent, in which case its arm sets `next` to the sub-form whose value is its own
and `break dispatch`es instead of `return`ing -- the same one rule the wasm backend's
tail flag has (`.kb/wasm-tail-calls.md`).

## The operator table is three methods, split by KIND

`evalCons` (the loop, the hot arms), `rareOperatorExpansion` (the rare built-in macros:
answers the expansion, which the loop continues with) and `evalConsRareOperator` (the rare
special forms and primitive calls: answers a VALUE, which ends the frame). The split is by
kind because the loop must know which; `evalConsRareOperator` used to hold both, calling
`eval` on its expansions, which cost a frame per `multiple-value-bind`/`flet`/`the` in a
tail. An ordinary call falls through all three switches (three misses, was two), which the
timings below do not resolve. `LispEvaluatorHotMethodSizeTest` keeps each under HotSpot's
8000-bytecode `HugeMethodLimit` (`.kb/hot-path-method-size.md`): `evalCons` is 6,812
bytecodes (was 5,516), `rareOperatorExpansion` 4,446, `evalConsRareOperator` under 2,500.
`javac` duplicates a `finally` at every `return` inside its `try`, which is why the loop's
arms assign `result` and `break frame` to ONE exit instead of returning: ~150 returns
times a 14-byte `finally` copy would have crossed the cliff by itself.

## `or`'s last form is a tail

`(or x .. z)` lowered to `(cond (x) .. (z))`, whose bodyless last clause bound `z` to a
temporary: one value, one frame kept. It is `(cond (x) .. (t z))` now
(`LispMacroExpander.expandOr`), on all four backends -- CLHS 7.4: the last form is in the
position of the whole `or`, all its values returned. `(multiple-value-list (or nil
(values 1 2)))` is `(1 2)` (SBCL prints the same; it was `(1)`), and `(or (assoc ..)
(walk ..))` is a tail call. `cond`'s own bodyless clause keeps its primary-value-only
semantics (`.kb/multiple-values.md`). Scheme's `or` already desugared its last operand
bare (`SchemeLowering.desugarOr`).

## Measurements (2026-09-19, linux-x64, Xeon E5-2697A v4, Oracle GraalVM 25.0.4, load 3-6)

**Depth, the CLI's 16 MiB worker, largest passing (binary search, one JVM per trial),
before -> after.** A tail call through a procedure value, Scheme `(define (g self n) (if (=
n 0) 'done (self self (- n 1))))`: 14,796 -> 5,000,000 passes (the probe's ceiling, 7 s);
Common Lisp `(funcall self self (- n 1))`: 14,171 -> 5,000,000; mutual `defun`s
`ev`/`od`: 13,468 -> 5,000,000. The NON-tail `(defun depth (n) (if (= n 0) 0 (+ 1 (depth
(- n 1)))))`: 10,435 -> 35,726 -- two Java frames per Lisp call (`eval` -> `evalArgs` ->
`eval`) where there were thirteen (`eval` -> `evalConsClassifyingRawFailures` -> `evalCons`
-> `apply` -> `runBlockIn` -> `eval` -> ... -> `evalIf` -> `eval`), so the ceiling of a
non-tail recursion is 3.4x higher too. The native binary runs the same interpreter: the
three tail shapes reach the same 5,000,000 there, and the non-tail `depth` 55,151 -- its
AOT frames are smaller than the JIT's.

**Time, whole process, alternating process pairs on one tree (baseline jar vs this
change), before -> after**: `fib 32` Common Lisp 4.72-5.26 -> 4.49-4.68 s, Scheme
5.32-5.64 -> 4.95-5.20 s; 300K shallow `(parity (remainder k 4))` (internal `ev?`/`od?`,
`.kb/scheme-frontend.md`) 4.22-4.41 -> 3.59-3.99 s; 30K `(parity 100)` 4.82-4.99 ->
4.65-4.78 s; `evalfib` (`examples/scheme/evaluator.scm` running `(fib 24)`) 32.6-38.6 ->
25.2-26.3 s. Every row prints the same last line on both arms. Nothing got slower: the
loop's extra switch miss per ordinary call is inside the gain of the frames not entered.

**Blast radius.** Output is byte-identical on every program the suite runs; the observable
changes are the depth ceilings above and `or`'s multiple values. Compiled programs
(`-o`) are untouched except through `expandOr`, whose new shape is smaller.

## Tests

`LispEvaluatorTest.aTailCallRunsInConstantStack` (100,000 deep through a value, mutual
defuns, `apply`, a lambda head, `labels`, `return-from`, and `progn`/`let`/`let*`/`cond`/
`when`/`unless`/`block`/`case`/`multiple-value-bind`/`the`/`or`/`and`),
`#aTailPositionThatKeepsItsFrameStillUnwindsItsBookkeeping` (SBCL prints the same),
`#aReturnFromReachesTheActivationWhoseBlockTheClosureCaptured` (SBCL prints the same),
`#aFuncallInTailPositionKeepsTheBuiltInsHandlerBindSeam`,
`#aGoInTheTailOfAFunctionCalledFromAStatementJumpsToTheStatementsTagbody`, and the
`.todo/901` trio; `RontoLispCliTest.aSchemeTailCallThroughAProcedureValueRunsInConstantStackOnTheInterpreter`
(a session and a file, 300,000 deep); `LispEvaluatorHotMethodSizeTest`. The REPL's
recovery after an overflow stays pinned by
`RontoLispCliTest.aStackOverflowAtTheReplIsReportedAndTheSessionKeepsItsDefinitions`. The
wasm side of the same programs: `.kb/wasm-tail-calls.md`; the JVM stays bounded
(`.todo/911`).
