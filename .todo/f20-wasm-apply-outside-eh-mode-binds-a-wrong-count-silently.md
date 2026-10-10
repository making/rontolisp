# f20. wasm: `apply` outside EH mode binds a wrong count silently

Difficulty: Medium

A wasm module with no handler landing pad (not EH mode) has no `_arity_chk`, so an `apply`
whose list does not fit the callee's lambda list answers a value: the spread walk binds nil
past the list's end and drops its surplus. Measured 2026-10-10 (wasmtime, Preview 1; the
2026-10-08 jar the same):

```lisp
(defun one (x) x)
(print (apply (car (list (function one))) (list 1 2)))  ; 1 (interpreter, JVM: program-error)
(print (apply (function one) (list 1 2)))               ; 1
```

A `funcall` with a wrong count traps there (`unreachable`, the per-arity dispatcher's miss), so
the module at least stops. A Clojure program reaches the silent arm through every call of a
function value (`%clojure-call` applies): `(println (map one [1 2] [3 4]))` without a `try` prints
`(1 2)` where the oracle throws.

## Plan

1. Decide what a no-handler module does on a wrong `apply` count: trap like the dispatch miss
   (a guard that costs only the `unreachable`), or the entry report's text
   (`.kb/error-handling.md`, "An uncaught condition reports ONE line").
2. Implement on both wasm legs; a `WasmLispCompilerIntegrationTest` case without a handler.
