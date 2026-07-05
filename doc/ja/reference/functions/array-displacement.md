# array-displacement

`(array-displacement array)`

2つの値を返します: [`make-array`](make-array.md) の `:displaced-to` で `array` の変位先に指定された配列と、作成時の `:displaced-index-offset` です。displaced でない配列の場合、値は nil と 0 です。2番目の値は `multiple-value-bind` などの多値コンシューマを通してのみ観測できます。

```lisp
(defparameter *base* (make-array 5 :initial-element 0))
(defparameter *view* (make-array 2 :displaced-to *base* :displaced-index-offset 3))
(multiple-value-bind (target offset) (array-displacement *view*)
  (list (eq target *base*) offset)) ; => (t 3)
(array-displacement *base*) ; => nil
```
