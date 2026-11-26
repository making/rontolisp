# array-total-size

`(array-total-size array)`

`array` の全要素数、つまりすべての次元にわたるサイズの積を返します。ランク 1 のベクタでは単にその長さ、ランク 2 の配列では行数と列数の積、ランク 0 の配列では (空の積として) 1 になります。ランク 0 の配列はちょうど 1 要素を保持するためです。[`array-dimensions`](array-dimensions.md) も参照してください。

文字列は文字のランク 1 配列なので、全要素数はその容量です。フィルポインタを持つ文字ベクタでは [`length`](length.md) より大きくなります。

```lisp
(array-total-size (vector 1 2 3)) ; => 3
(array-total-size (make-array '(2 3))) ; => 6
(array-total-size (make-array nil)) ; => 1
(array-total-size "abc") ; => 3
(array-total-size (make-array 5 :element-type 'character :fill-pointer 2)) ; => 5
```
