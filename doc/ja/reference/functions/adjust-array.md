# adjust-array

`(adjust-array array new-dimensions &key initial-element fill-pointer)`

`array` を `new-dimensions` (ベクタなら整数、それ以外は `array` と同じランクのリスト) にリサイズし、両方の形状で有効な添字位置にある要素を保持します。行列のリサイズでは `(i, j)` の要素は `(i, j)` に残り、同じフラット位置には移動しません。新しいセルは `:initial-element` (デフォルトは nil) で埋められます。[`:adjustable`](make-array.md) で作成された配列はその場で調整され、引数自身 (`eq`) が返るため、すべての参照から新しい形状が見えます。それ以外の場合は新しい配列が返り、元の配列は変更されません。いずれの場合も戻り値を使用してください。`:fill-pointer` を明示しない場合は配列自身の[フィルポインタ](fill-pointer.md)が引き継がれます (新しいサイズに収まらない場合はエラー)。`t` は新しいサイズ、整数はその位置に設定します。displaced ビューの調整や `:displaced-to` の指定はサポートされておらず、エラーを通知します。

```lisp
(defparameter *v* (make-array 3 :adjustable t :initial-element 1))
(eq (adjust-array *v* 5 :initial-element 9) *v*) ; => T
*v* ; => #(1 1 1 9 9)
(adjust-array (make-array '(2 2) :initial-element 5) '(2 3) :initial-element 0) ; => #2A((5 5 0) (5 5 0))
```
