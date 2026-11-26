# prog*

`(prog* (bindings...) {tag | form}...)`

Like [`prog`](prog.md) with sequential ([`let*`](let-star.md)-style) bindings: each init form sees the variables bound before it.

```lisp
(prog* ((x 5) (y (* x 2)))
  (return (+ x y))) ; => 15
```
