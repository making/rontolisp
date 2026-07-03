# array-total-size

`(array-total-size array)`

`array` の全要素数、つまりすべての次元にわたるサイズの積を返します。ランク 1 のベクタでは単にその長さ、ランク 2 の配列では行数と列数の積になります。[`array-dimensions`](array-dimensions.md) も参照してください。

```lisp
(array-total-size (vector 1 2 3)) ; => 3
(array-total-size (make-array '(2 3))) ; => 6
```
