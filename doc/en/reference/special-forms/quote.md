# quote

`(quote expr)` or `'expr`

Returns `expr` without evaluating it, yielding the literal datum -- a symbol, list, or atom -- as written. This is how you produce list and symbol literals; the reader macro `'expr` is shorthand for `(quote expr)`. The argument is never evaluated, so `(quote (+ 1 2))` returns the three-element list, not `3`.

```lisp
(quote (a b c)) ; => (A B C)
```
