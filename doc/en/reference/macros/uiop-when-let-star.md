# uiop:when-let*

`(uiop:when-let* ((var form)...) body...)`

The sequential [`uiop:when-let`](uiop-when-let.md): each binding's form sees the
variables bound before it, and the first variable that comes out nil
short-circuits the whole form to nil **without evaluating the remaining
forms**. That is the difference that matters — a later form may safely assume
the earlier bindings are non-nil.

```lisp
(list (uiop:when-let* ((a 5) (b (* a 2))) (+ a b))
      (uiop:when-let* ((a nil) (b (/ 1 a))) b))   ; => (15 NIL)
```

The second line never evaluates `(/ 1 a)`, which would signal.

`uiop` is ASDF's portability layer, not part of Common Lisp: the name is only
reachable with the `uiop:` qualifier.

## Backend support

Works on all four backends: it is a built-in macro expansion shared by the
interpreter and both compilers. Like the other built-in macros it has no
function value (`#'uiop:when-let*` is an error).
