# fill-pointer

`(fill-pointer vector)`

[`make-array`](make-array.md) の `:fill-pointer` で作成されたベクタのフィルポインタを返します。フィルポインタはベクタの実効長です。`length` や印字はフィルポインタで止まりますが、[`aref`](aref.md) はバッキングストレージ全体にアクセスできます。`setf` 可能な場所であり、`(setf (fill-pointer v) n)` で 0 からベクタの総サイズまでの任意の位置に移動できます。配列にフィルポインタがない場合はエラーを通知します (事前に [`array-has-fill-pointer-p`](array-has-fill-pointer-p.md) で確認してください)。

```lisp
(defparameter *v* (make-array 5 :fill-pointer 2 :initial-element 0))
(fill-pointer *v*) ; => 2
(setf (fill-pointer *v*) 4) ; => 4
(length *v*) ; => 4
```
