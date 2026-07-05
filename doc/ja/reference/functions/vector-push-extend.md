# vector-push-extend

`(vector-push-extend value vector &optional extension)`

[`vector-push`](vector-push.md) と同様ですが、ベクタが満杯の場合に nil を返す代わりにバッキングストレージを (少なくとも `extension` 要素分、デフォルトは 1) 拡張するため、プッシュは常に成功し、新しいインデックスが返されます。`:adjustable` で作成されたかどうかに関係なく、フィルポインタを持つ任意のベクタを拡張できます (一般的な慣行に合わせています。[`adjustable-array-p`](adjustable-array-p.md) はフラグをそのまま報告します)。ベクタにフィルポインタがない場合はエラーを通知します。

```lisp
(defparameter *v* (make-array 1 :fill-pointer 0 :adjustable t))
(vector-push-extend 10 *v*) ; => 0
(vector-push-extend 20 *v* 4) ; => 1
*v* ; => #(10 20)
```
