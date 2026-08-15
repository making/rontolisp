# Run-time `eval`/`compile` of a user-macro form answers nil silently on the compiled backends

Difficulty: Low

Found in `.todo/372`'s spike: rove's `(deftest (name :compile-at :run-time)
...)` wraps the body in `(funcall (compile nil '(lambda () (with-testing-with-options
...))))`, and on the JVM/WASM the test ran, recorded nothing, printed nothing.

```lisp
(defmacro m (x) `(print (list :m ,x)))
(defun f (y) (print (list :f y)) y)
(funcall (compile nil '(lambda () (f 1))))    ; (:F 1) on all backends
(funcall (compile nil '(lambda () (m 2))))    ; interpreter (:M 2); JVM/WASM: NIL, silently
(eval '(m 4))                                 ; same
```

The embedded eval runtime (`.kb/eval-runtime.md`) has no macro table -- expected
and documented -- but an unknown operator evaluates to nil instead of signaling
`undefined-function`, and the `compile` doc page says "any other run-time
`compile` call signals an error", which the first line above contradicts too
(a defun-calling lambda WORKS through `compile-runtime.lisp`'s `eval` route).

Two honest outcomes; pick one and make the doc say it: (a) an unknown operator
in the eval runtime signals `undefined-function` naming it (cheap, correct for
functions AND macros, catchable per `.todo/379`); (b) additionally carry the
program's user macros into the runtime as expander closures so `eval` of a
macro form works (the interpreter's `expandMacroCall` shape; heavier, needs
`.todo/378`'s table). (a) is the floor: silent nil is the one thing a runtime
must not do. `.todo/372` documents `:compile-at :run-time` as interpreter-only
either way.

Acceptance: the three lines above pinned on all four backends (ci-spec + the
backend suites); the `compile` and `eval` doc pages' limitation sentences.
