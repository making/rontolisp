# vector-set!

`(vector-set! vector k obj)`

Stores `obj` at index `k` of `vector`, returning the unspecified value. Vector literals are mutable too: R7RS makes modifying a literal an error, rontolisp does not refuse it.

```scheme
(let ((v (vector 1 2 3))) (vector-set! v 0 'x) v) ; => #(x 2 3)
(define v (vector 1 2 3))
(vector-set! v 0 'x)
v ; => #(x 2 3)
```
