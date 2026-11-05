# quote

`(quote expr)` or `'expr`

Returns `expr` without evaluating it, yielding the literal datum -- a symbol, list, or atom -- as written. This is how you produce list and symbol literals; the reader macro `'expr` is shorthand for `(quote expr)`. The argument is never evaluated, so `(quote (+ 1 2))` returns the three-element list, not `3`.

```lisp
(quote (a b c)) ; => (A B C)
```

The literal keeps what a `#n=` reader label wrote: a node the label shares is one object, and a label inside its own datum closes a circle, through a list, an array or a structure.

```lisp
(let ((a '(#1=(x) #1#))) (eq (car a) (cadr a))) ; => T
(let ((a '#2=(x #2#))) (eq a (cadr a))) ; => T
```
