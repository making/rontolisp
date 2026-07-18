# make-array

`(make-array dimensions &key initial-element initial-contents element-type fill-pointer adjustable displaced-to displaced-index-offset)`

新しい配列を作成して返します。`dimensions` はランク 1 のベクタの場合は整数、任意のランクの配列の場合は空でない整数のリストです。`:initial-element` はすべてのセルを指定した値に設定します。デフォルトは nil です。要素は行優先で格納され、`aref` を介して O(1) でアクセスできます。配列は同一性 (`eq`) で比較されるため、異なる 2 つの配列が `equal` になることはありません。`make-array` と `aref` は第一級の関数値ではありません。`#'make-array` は利用できないため、直接呼び出してください。

`:fill-pointer` (ランク 1 のみ) はベクタに[フィルポインタ](fill-pointer.md)を与えます。整数はその位置に、`t` はベクタサイズに設定します。フィルポインタは実効長であり、`length` や印字はフィルポインタで止まります (`aref` はストレージ全体にアクセスできます)。[`vector-push`](vector-push.md)/[`vector-pop`](vector-pop.md)/[`vector-push-extend`](vector-push-extend.md) が操作するのもこのフィルポインタです。`:adjustable` は配列を可変長としてマークし、[`adjustable-array-p`](adjustable-array-p.md) がそのまま報告します。可変長配列は [`adjust-array`](adjust-array.md) でその場でリサイズされます。`:initial-contents` はリストから配列を充填します (row-major。コンパイル系バックエンドではランク 1 のみ)。`:element-type 'double-float`/`'single-float` (フィルポインタ/可変長/displacement なし) はパックド浮動小数点表現を選択し、同じ条件の `:element-type 'character` は**文字列**を作ります (ランク 1 の文字配列は文字列そのものであり、[`make-string`](make-string.md) の結果と同じ形です)。インタープリタでは `:fill-pointer`/`:adjustable` **付き**の `:element-type 'character` はフィルポインタ付きの可変文字列を作ります (`vector-push-extend` で文字を追加でき、文字列として印字・比較されます)。JVM / WASM コンパイラでは同じ呼び出しは一般の文字ベクタを作ります -- フィルポインタ操作は動きますが結果は文字列ではありません (`stringp` は nil で、`#(...)` として印字されます)。それ以外の要素型は受け付けられますが無視されます (要素型はそれ以外では追跡されません。一般配列では [`array-element-type`](array-element-type.md) は `t` を返します)。

`:displaced-to` は、ストレージを割り当てる代わりに別の配列のストレージへのビューを構築します。ビューの (行優先の) 要素 `i` はターゲットの要素 `i + offset` を読み書きします (`:displaced-index-offset` のデフォルトは 0)。変更は双方向に見えます。ビューは独自の次元を持ち (ターゲットとランクが異なってもよく、例えば行列の行に対するベクタビューが作れます)、ターゲット内に収まる必要があり、[`array-displacement`](array-displacement.md) で調べられます。displaced ビューは `:fill-pointer`/`:adjustable`/`:initial-element` と併用できず、それ自体を adjust することもできません。

```lisp
(let ((a (make-array 3 :initial-element 0)))
  (aref a 0)) ; => 0
(length (make-array 5 :fill-pointer 2 :initial-element 0)) ; => 2
(let* ((base (make-array 4 :initial-element 1))
       (view (make-array 2 :displaced-to base :displaced-index-offset 1)))
  (setf (aref view 0) 9)
  (aref base 1)) ; => 9
```
