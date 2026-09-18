# vector-fill!

`(vector-fill! vector fill)` `(vector-fill! vector fill start)` `(vector-fill! vector fill start end)`

Stores `fill` in every element of `vector`, or in the elements from `start` (inclusive) to `end` (exclusive, default the end of the vector), returning the unspecified value.

```scheme
(let ((v (make-vector 3 0))) (vector-fill! v 7) v) ; => #(7 7 7)
(define w (make-vector 4 0))
(vector-fill! w 7 1 3)
w ; => #(0 7 7 0)
```
