# macroexpand

`(macroexpand form)`

Repeats [`macroexpand-1`](macroexpand-1.md) on the top-level form until it stops expanding, and returns the result. Like `macroexpand-1`, only the operator position is expanded — macro calls in subforms stay unexpanded — and the same limitations apply (no second return value, no environment argument, and on the compilation path the argument must be a literal quoted form, folded at compile time).

```lisp
(defmacro inner (x) `(+ ,x 1))
(defmacro outer (x) `(inner ,x))
(macroexpand '(outer 41)) ; => (+ 41 1)
```

Subforms are not walked:

```lisp
(macroexpand '(when a (when b c))) ; => (if a (when b c) nil)
```
