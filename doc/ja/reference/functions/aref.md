# aref

`(aref array &rest subscripts)`

指定された 0 始まりの添字 (次元ごとに 1 つ: ランク 1 のベクタには 1 つ、ランク 2 の配列には 2 つ、以降も同様) における `array` の要素を返します。ランクに依存しないフラットなアクセスには [`row-major-aref`](row-major-aref.md) が使えます。要素を変更するには、`aref` を `setf` の場所として使います: `(setf (aref array i j) value)`。これは `incf`/`decf`/`push` でも機能します。`aref` は第一級の関数値として公開されていない（`#'aref` は使えない）ため、直接呼び出してください。

```lisp
(let ((a (make-array 3 :initial-element 0)))
  (setf (aref a 1) 9)
  (aref a 1)) ; => 9
```
