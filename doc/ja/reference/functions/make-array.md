# make-array

`(make-array dimensions &key initial-element fill-pointer adjustable)`

新しい配列を作成して返します。`dimensions` はランク 1 のベクタの場合は整数、任意のランクの配列の場合は空でない整数のリストです。`:initial-element` はすべてのセルを指定した値に設定します。デフォルトは nil です。要素は行優先で格納され、`aref` を介して O(1) でアクセスできます。配列は同一性 (`eq`) で比較されるため、異なる 2 つの配列が `equal` になることはありません。`make-array` と `aref` は第一級の関数値ではありません。`#'make-array` は利用できないため、直接呼び出してください。

`:fill-pointer` (ランク 1 のみ) はベクタに[フィルポインタ](fill-pointer.md)を与えます。整数はその位置に、`t` はベクタサイズに設定します。フィルポインタは実効長であり、`length` や印字はフィルポインタで止まります (`aref` はストレージ全体にアクセスできます)。[`vector-push`](vector-push.md)/[`vector-pop`](vector-pop.md)/[`vector-push-extend`](vector-push-extend.md) が操作するのもこのフィルポインタです。`:adjustable` は配列を可変長としてマークし、[`adjustable-array-p`](adjustable-array-p.md) がそのまま報告します。`:element-type` は受け付けられますが無視されます (要素型は追跡されません。[`array-element-type`](array-element-type.md) は常に `t` を返します)。

```lisp
(let ((a (make-array 3 :initial-element 0)))
  (aref a 0)) ; => 0
(length (make-array 5 :fill-pointer 2 :initial-element 0)) ; => 2
```
