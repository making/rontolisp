# Interpreter: proper tail calls through a loop in `eval`, not a trampoline

Difficulty: High

Follow-up of `.todo/899`. After it, a tail call through a procedure value runs in constant
stack on both wasm targets (`.kb/wasm-tail-calls.md`) and the JVM has no mechanism; the
interpreter -- the default run path (`.kb/default-run-path.md`) -- recurses in Java.

## The measurement (2026-09-19, linux-x64, Java 25)

`(define (g self n) (if (= n 0) 'done (self self (- n 1))))` reaches 15,497 on the CLI's
16 MiB worker, about 1 KiB of Java stack per Lisp call: `eval` -> `evalCons` -> the
`funcall` built-in -> `apply` -> the body's `eval` -> `evalIf` -> `eval` ... `--stack` raises
it (`.kb/interpreter-stack.md`). A trampoline (a tail call answers a bounce, every call site
drives it) was measured and rejected: 2.2x on `fib 32`, 16x per bounced call
(`.kb/scheme-frontend.md`, "Tail-call groups").

## The plan

A tree-walking interpreter gets proper tail calls without bounces: `eval` becomes a loop,
and a form in tail context -- an `if` arm, the last form of `progn`/`let`/a body, the
application of a `LispLambda` -- REPLACES the current form and environment and continues
the loop instead of recursing. No allocation per call; usually faster, since fewer Java
frames are entered. What must keep its frame, because something runs after the value:

- `apply`'s `finally`: `functionBodyDepth--` and the pop of a dynamically bound parameter
  (`dynamicParams`) -- a lambda with a special parameter is not a tail call site;
- a `let` binding a special, `progv`, `handler-case`/`handler-bind` frames,
  `unwind-protect`, `multiple-value-prog1`, `catch`;
- the block scope `runBlockIn` installs for a defun body (`installBlock`,
  `BlockReturnSignal` identity): a tail call out of a block must not keep the block's scope
  alive as the callee's, and `return-from` inside the callee must still find ITS block;
- `controlState()`/`restore()` bookkeeping the REPL relies on after a `StackOverflowError`.

Measure before building: `fib 32`, `evalfib` (`examples/scheme/evaluator.scm` running
`(fib 27)`), the 300K shallow `(parity (remainder k 4))` loop and the 30K `(parity 100)`
of `.kb/scheme-frontend.md`, so the loop's cost on non-tail code is known. Adjacent:
`LispEvaluator.evalTagbodyStatement` (`.todo/901`, done): a tail-context walk of a tagbody
statement through `if`/`progn`/`let`/`let*`/`when`/`unless`/`cond` that answers a label
index instead of throwing `GoSignal`; the loop generalizes it (a tagbody statement is a tail
context whose continuation is a jump) and should absorb it rather than sit beside it. Pin
with a wasm+interpreter depth test (the JVM stays bounded, so no four-backend
`scheme-spec.yaml` case can hold it).
