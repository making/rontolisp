# c14. A closure over a special binding reads its capture under another binding

Difficulty: High

A special `let` -- and since c06 a parameter named like a special, which lowers into one --
DUAL-binds on the compile paths: the dynamic store and a lexical slot a closure built in the
body captures (`.kb/dynamic-special-variables.md`, "inside a closure the CAPTURE wins"). The
interpreter dual-binds too but reads dynamic-first everywhere, closures included: it answers
the capture only when no binding of the name is active. The two agree for a closure called
after the extent with no other binding active (cl-ppcre's `end-string`, the case the dual
binding exists for) and disagree when it is called inside ANOTHER binding of the name.

Measured 2026-10-03 (`5affa8ac5` and after c06, all four backends):

```lisp
(defvar *y* :top)
(defun mk () (let ((*y* :inner)) (lambda () *y*)))
(print (let ((*y* :outer)) (funcall (mk))))
(print (funcall (mk)))
```

Interpreter `:OUTER :INNER`; JVM, WASM Preview 1 and component `:INNER :INNER`. SBCL
answers `:OUTER :TOP` (a special reference reads the current binding, never a capture); the
interpreter's second answer is the dual binding's deliberate departure for names the
program-wide `(declare (special ...))` reading made special.

## Plan

- Decide the rule once for every backend: the interpreter's (active binding first, else the
  capture) keeps cl-ppcre working and matches SBCL whenever a binding is active.
- JVM: a closure read tests the `_d$` cell (`_dget` answers null when the thread has no
  binding) before the capture.
- WASM: shallow binding over the module global leaves no "is a binding active" fact to test;
  it needs one (a per-special depth, or a binding marker the restore clears), and
  `--reentrant`'s task record its twin. Measure the cost on cl-ppcre and on a closure-heavy
  special program before choosing.
- Pin it in ci-spec beside `special-let-restores-on-every-exit`.
