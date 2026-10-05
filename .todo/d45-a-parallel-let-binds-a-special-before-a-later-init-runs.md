# d45. A parallel let binds a special before a later init runs

Difficulty: Medium

`let` is parallel: every init runs before any variable is bound. The JVM and both WASM
let compilers bind one variable at a time, and `ParallelLetStaging` stages an init only when
it NAMES an earlier variable, so an init that reads a special through a call sees the new
binding.

Measured 2026-10-05 (`447575529`, all four backends):

```lisp
(defvar *x* 0)
(defun f () *x*)
(print (let ((*x* 1) (y (f))) (list *x* y)))
```

SBCL and the interpreter `(1 0)`; JVM, WASM Preview 1 and component `(1 1)`.

## Plan

- Stage the inits of a `let` that binds a special and has a later init that can run code
  (a call, not a constant or a variable) through temporaries, as `ParallelLetStaging` does for
  a named reference -- or bind the specials after every init, in the let compilers.
- Measure the size cost on cl-ppcre and the ci-spec program; pin it beside
  `special-variable-dynamic-binding` in ci-spec and the three backend suites.
