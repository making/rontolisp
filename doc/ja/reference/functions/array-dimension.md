# array-dimension

`(array-dimension array axis-number)`

0 始まりの `axis-number` で指定した `array` の次元のサイズを返します。ランク 1 のベクタで有効な軸は 0 のみ、ランク 2 の配列では軸は 0 と 1 です。すべてのサイズを一度に取得するには [`array-dimensions`](array-dimensions.md) を使ってください。

```lisp
(array-dimension (make-array '(2 3)) 0) ; => 2
(array-dimension (make-array '(2 3)) 1) ; => 3
```
