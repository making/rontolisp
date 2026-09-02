# adjust-array

`(adjust-array array new-dimensions &key initial-element fill-pointer)`

`array` を `new-dimensions` (ベクタなら整数、それ以外は `array` と同じランクのリスト) にリサイズし、両方の形状で有効な添字位置にある要素を保持します。行列のリサイズでは `(i, j)` の要素は `(i, j)` に残り、同じフラット位置には移動しません。新しいセルは `:initial-element` で埋められます。省略時はその配列の要素型自身のゼロ値になります。これは [`make-array`](make-array.md) が値を指定されなかった要素に与える値と同じで、文字配列なら `#\Space`、宣言された整数幅や浮動小数点型なら `0` または `0.0`、要素型 `t` なら `nil` です。[`:adjustable`](make-array.md) で作成された配列はその場で調整され、引数自身 (`eq`) が返るため、すべての参照から新しい形状が見えます。それ以外の場合は新しい配列が返り、元の配列は変更されません。いずれの場合も戻り値を使用してください。`:fill-pointer` を明示しない場合は配列自身の[フィルポインタ](fill-pointer.md)が引き継がれます (新しいサイズに収まらない場合はエラー)。`t` は新しいサイズ、整数はその位置に設定します。[要素型](array-element-type.md)は変更されません。調整不可能な配列を調整して返される新しい配列も、元の配列が保持するよう宣言された要素型を覚えているため、調整後の文字ベクタは引き続き文字列です。displaced ビューの調整や `:displaced-to` の指定、パックされた整数ベクタの調整はサポートされておらず、エラーを通知します。

```lisp
(defparameter *v* (make-array 3 :adjustable t :initial-element 1))
(eq (adjust-array *v* 5 :initial-element 9) *v*) ; => T
*v* ; => #(1 1 1 9 9)
(adjust-array (make-array '(2 2) :initial-element 5) '(2 3) :initial-element 0) ; => #2A((5 5 0) (5 5 0))
```
