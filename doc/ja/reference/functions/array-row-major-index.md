# array-row-major-index

`(array-row-major-index array &rest subscripts)`

指定された添字 (次元ごとに 1 つ) における `array` の要素の、0 始まりのフラットな行優先 (row-major) インデックスを返します。次元サイズに対する畳み込み `((s0 * d1 + s1) * d2 + s2) ...` です。ランク 0 の配列は添字を取らず、その空の畳み込みは 0 になります。結果は [`row-major-aref`](row-major-aref.md) にそのまま渡せるインデックスです。

```lisp
(array-row-major-index (make-array (list 2 3)) 1 1) ; => 4
(array-row-major-index (make-array nil)) ; => 0
```
