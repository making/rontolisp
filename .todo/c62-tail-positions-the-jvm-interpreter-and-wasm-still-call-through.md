# c62. Tail positions the JVM, the interpreter and wasm still call through

Difficulty: Medium

Measured 2026-10-03 (exec jar, wasmtime 49), each a self call 1,000,000 deep, `(defun f (n)
<form>)` then `(print (f 1000000))`:

- Compiled JVM (`java Prog`, the 16 MiB worker) ends with `StackOverflowError` when the
  self call sits in the tail of `multiple-value-bind`, `destructuring-bind`,
  `symbol-macrolet`, `multiple-value-call` (`(multiple-value-call #'f (- n 1))`), a lambda
  head (`((lambda (k) (f k)) (- n 1))`) or a `return` out of a `dolist` body
  (`(dolist (x (list 1)) (return (if (= n 0) :ok (f (- n 1)))))`). The interpreter and wasm
  answer each of these but the two below.
- Interpreter: the `dolist` `return` shape overflows -- the loop body runs as a tagbody
  statement, a frame whose owner is not the block (`.kb/interpreter-tail-calls.md`).
- wasm and the component: the lambda head traps (`call stack exhausted`); the general
  indirect call is a plain call by design (`.kb/wasm-tail-calls.md`).

`prog1` is not in this list on purpose: its value form is no tail on any backend.

## Plan

- JVM: find where the self-tail jump and the tail-bounce decide tail position
  (`.kb/jvm-self-tail-calls.md`, `.kb/jvm-tail-bounce.md`) and hand it through the
  expansions of these forms, the way the wasm backend's `compileExpansion` does.
- Interpreter: let a tail `return`/`return-from` out of a tagbody statement reach the frame
  that owns the block (a token like `TagbodyLabels.jump`), or record why not.
- wasm: compile a lambda head in tail position as the `let` it is equivalent to, or a
  `return_call` of the dispatcher.

## Pin

- `ci-spec.yaml` (all four backends): each shape 100,000 deep.
