# c56. `*error-output*` is a fresh value per read on the JVM and wasm; wasm stream direction predicates answer T

Difficulty: Medium

Measured 2026-10-03 on all four backends:

```lisp
(print (list (eq *error-output* *error-output*)
             (let ((a *error-output*) (b *error-output*)) (eq a b))))
(print (list (input-stream-p *error-output*)
             (output-stream-p (make-string-input-stream "a"))
             (input-stream-p (make-string-output-stream))))
```

The interpreter answers `(T T)` `(NIL NIL NIL)`; the JVM `(NIL NIL)` `(NIL NIL NIL)`; wasm
and the component `(NIL NIL)` `(T T T)`. Two reads of `*error-output*` are not `eq` on the
compile backends (the seeded default is `StreamDesignators.standardError()`, a constructor
FORM, apparently evaluated per read), so Clojure `(identical? *err* *err*)` is false there;
and wasm's `input-stream-p`/`output-stream-p` answer T for the wrong direction.

Expected: the interpreter's answers on every backend, pinned in ci-spec.
