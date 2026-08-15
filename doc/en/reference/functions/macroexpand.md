# macroexpand

`(macroexpand form)`

Repeats [`macroexpand-1`](macroexpand-1.md) on the top-level form until it stops expanding, and returns the result plus the same `expanded-p` second value. Like `macroexpand-1`, only the operator position is expanded — macro calls in subforms stay unexpanded — the environment argument is ignored, and on the compilation path only a literal quoted argument is folded to its expansion (a computed one answers the form unchanged, or signals when the form is a macro call).

```lisp
(defmacro inner (x) `(+ ,x 1))
(defmacro outer (x) `(inner ,x))
(macroexpand '(outer 41)) ; => (+ 41 1)
```

Subforms are not walked:

```lisp
(macroexpand '(when a (when b c))) ; => (IF A (WHEN B C) NIL)
```
