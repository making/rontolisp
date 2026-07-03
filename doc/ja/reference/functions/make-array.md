# make-array

`(make-array dimensions &key initial-element)`

新しい配列を作成して返します。`dimensions` はランク 1 のベクタの場合は整数、任意のランクの配列の場合は空でない整数のリストです。`:initial-element` はすべてのセルを指定した値に設定します。デフォルトは nil です。要素は行優先で格納され、`aref` を介して O(1) でアクセスできます。配列は同一性 (`eq`) で比較されるため、異なる 2 つの配列が `equal` になることはありません。`make-array` と `aref` は第一級の関数値ではありません。`#'make-array` は利用できないため、直接呼び出してください。

```lisp
(let ((a (make-array 3 :initial-element 0)))
  (aref a 0)) ; => 0
```
