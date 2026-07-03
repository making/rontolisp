# row-major-aref

`(row-major-aref array index)`

0 始まりのフラットな行優先 (row-major) インデックス `index` における `array` の要素を、配列のランクに関係なく返します。たとえば 2x3 配列の要素 `(i, j)` はフラットインデックス `i * 3 + j` にあります。添字の組からフラットインデックスを計算するには [`array-row-major-index`](array-row-major-index.md) を使います。要素を変更するには、`row-major-aref` を `setf` の場所として使います: `(setf (row-major-aref array k) value)`。`aref` と同様、第一級の関数値としては公開されていないため、直接呼び出してください。

```lisp
(let ((m (make-array (list 2 3) :initial-element 0)))
  (setf (row-major-aref m 4) 9)
  (aref m 1 1)) ; => 9
```
