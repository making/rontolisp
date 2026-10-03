# c09. On wasm a lambda inlined into the dispatcher calls back through it with a plain call

Difficulty: Medium

Measured 2026-10-03 (exec jar, wasmtime 49):

- `(defun mk (k n) (if (= n 0) k (mk (lambda (v) (funcall k v)) (- n 1))))` then
  `(print (funcall (mk (lambda (v) v) 100000) :cps))`: wasm and the component trap (call
  stack exhausted, every frame the arity dispatcher `_invoke_1`); the interpreter and the
  compiled JVM answer `:CPS`.
- The module's `_invoke_1` holds the continuation lambda's body as one of its arms, and that
  body's tail `(funcall k v)` is `call <_invoke_1>` then `return`, not `return_call`: one
  frame per link of the chain. That is `WasmInliner` rule (2) (`.kb/wasm-tail-calls.md`): a
  `return_call X` inside a moved body becomes `call X; br <wrapper>`. Its premise, "the
  stack is exactly as deep as the tail call left it", fails when the body moved to a
  `return_call` site (rule (1)): there the containing function's frame stays under `X`,
  where the lambda's frame had been replaced.

## Plan

- Keep a moved body's `return_call` as `return_call` when the site it moved to was itself a
  `return_call` (the containing function's result is the body's); measure the bytes on
  size-report and the examples. Check whether `.todo/c01`'s multi-arity `fn` `recur` (cause
  not identified) is the same fault.

## Pin

- `ci-spec.yaml` (all four backends): the continuation chain above, 100,000 deep.
