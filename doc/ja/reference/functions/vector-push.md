# vector-push

`(vector-push value vector)`

[`make-array`](make-array.md) の `:fill-pointer` で作成されたベクタのフィルポインタの位置に `value` を格納し、フィルポインタをインクリメントして、値が格納されたインデックスを返します。ベクタがすでに満杯の場合は (ベクタを変更せずに) nil を返します。代わりにベクタを拡張するには [`vector-push-extend`](vector-push-extend.md) を使用してください。ベクタにフィルポインタがない場合はエラーを通知します。

```lisp
(defparameter *v* (make-array 2 :fill-pointer 0))
(vector-push 10 *v*) ; => 0
(vector-push 20 *v*) ; => 1
(vector-push 30 *v*) ; => nil
*v* ; => #(10 20)
```
