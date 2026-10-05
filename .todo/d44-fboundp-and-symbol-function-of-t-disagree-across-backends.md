# d44. fboundp and symbol-function of T disagree across backends

Difficulty: Low

`T` is a symbol, not an fbound one: CL answers `(fboundp t)` with NIL and
`(symbol-function t)` / `(fdefinition t)` with an `undefined-function` error.

```lisp
(print (handler-case (fboundp t) (error (e) (list :error (princ-to-string e)))))
(print (handler-case (symbol-function t) (undefined-function () :undefined-function)
         (error (e) (list :error (princ-to-string e)))))
```

- Interpreter: `(:ERROR "FBOUNDP expects a symbol, got T")`, and the same type complaint for
  `symbol-function` / `fdefinition` (`LispEvaluator`, the "expects a symbol" checks do not take
  `T`). A walk over `cl-user` that asks `(fboundp s)` of each symbol dies on `T`.
- JVM: `NIL`, `:UNDEFINED-FUNCTION` (right).
- WASM P1: `NIL`, then `(symbol-function t)` traps (`unreachable`) instead of signalling a
  catchable `undefined-function`.

## Plan

- Failing cross-backend test first (a fixture shared by the four suites).
- Interpreter: let the symbol checks accept `T` (and NIL where they do not already).
- WASM: find the unreachable arm `symbol-function` of a non-function symbol reaches and make it
  signal `undefined-function` like the JVM.
