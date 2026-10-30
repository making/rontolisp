# h42. A `deftype` with parameters, used as a compound specifier, answers NIL from `typep`

Difficulty: Low

Found 2026-10-30 probing `trivial-types`.

```lisp
(defun plp (x) (listp x))
(deftype pl (&optional (et '*)) (declare (ignore et)) '(and list (satisfies plp)))
(list (typep '(1 2) '(pl integer)) (typep '(1 2) 'pl))   ; SBCL: (T T)   here: (NIL T)
```

`(trivial-types:proper-list integer)` and the rest of that library's parameterized types
answer NIL. Expand `(NAME args...)` through the deftype expander with the arguments bound,
on all four backends (`.kb/declarations-type-checks.md`). Add the failing case first.
