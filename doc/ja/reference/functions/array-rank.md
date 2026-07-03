# array-rank

`(array-rank array)`

`array` の次元数を返します。ベクタでは 1、2 次元配列では 2 で、より高いランクでも同様です。この値は [`array-dimensions`](array-dimensions.md) が返すリストの長さと等しくなります。

```lisp
(array-rank (vector 1)) ; => 1
(array-rank (make-array '(2 3))) ; => 2
```
