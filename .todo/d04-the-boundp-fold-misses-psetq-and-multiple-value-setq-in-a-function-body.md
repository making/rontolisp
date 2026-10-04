# d04. The boundp fold misses psetq and multiple-value-setq in a function body

Difficulty: Low

```lisp
(defun pp () (psetq *pa* 1 *pb* 2))
(pp)
(print (list (boundp '*pa*) *pa*))
(defun mv () (multiple-value-setq (*q* *r*) (floor 7 2)))
(mv)
(print (list (boundp '*q*) *q*))
```

SBCL and the interpreter print `(T 1)` / `(T 3)`; the JVM, wasm P1 and component print
`(NIL 1)` / `(NIL 3)`. `CompileTimeBoundp.scan` poisons a name a deferred body assigns only
through `setq`/`setf` (and nested `defun`), so it never sees these places and folds the
top-level probe to NIL. `GlobalVarCollector.collectAssignedPlaces` already reads the full set
(`setq`, `setf`, `psetq`, `psetf`, `multiple-value-setq`); the fold's scan should agree with it.
Add the program to `CompileTimeBoundpTest` and a four-backend pin (e.g. extend
`ProbedUnboundGlobalFixture`) first.
