# vector-fill!

`(vector-fill! vector fill)` `(vector-fill! vector fill start)` `(vector-fill! vector fill start end)`

`vector` の全要素、または `start`（含む）から `end`（含まない、既定はベクタの末尾）までの要素に `fill` を格納し、未規定値を返します。

```scheme
(let ((v (make-vector 3 0))) (vector-fill! v 7) v) ; => #(7 7 7)
(define w (make-vector 4 0))
(vector-fill! w 7 1 3)
w ; => #(0 7 7 0)
```
