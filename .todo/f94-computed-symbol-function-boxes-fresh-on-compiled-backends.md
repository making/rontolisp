# f94. A computed `(symbol-function x)` boxes fresh per resolution on the compiled backends

Difficulty: Medium

Found closing `.todo/f85` (2026-10-13): after f85, two STATIC `(function name)`
sites of one defun answer the same object on every backend (JVM `_funcref`,
wasm canonical closure globals, interpreter function namespace), so
`(eq #'f #'f)` is true everywhere. A COMPUTED resolution still boxes fresh per
evaluation on the compiled backends:

```lisp
(defun f (x) (+ x 1))
(let ((s 'f))
  (print (list (eq (symbol-function s) #'f) (eq (symbol-function s) (symbol-function s)))))
```

answers `(T T)` on the interpreter (the function namespace holds one object)
and `(NIL NIL)` on the JVM and both wasm backends (SBCL: `(T T)`). The literal
`(symbol-function 'f)` spelling delegates to the static path and is shared. An
`fdefinition` of a computed name goes through the same runtime box. Clojure
cannot reach the path (no computed function-name operator; `resolve` answers
vars).

## Plan

1. Decide the scope: route the runtime box through the canonical per-defun
   object (JVM: `_funcref`, already keyed by funcId; wasm: the runtime funcId
   needs a table the sparse per-site globals do not give), or document the
   deviation.
2. Cross-backend pins for `(eq (symbol-function s) #'f)` and two computed
   resolutions of one name, interpreter/JVM/wasm-P1/wasm-component; SBCL is
   the oracle for the CL spelling.
