# vector-pop

`(vector-pop vector)`

[`make-array`](make-array.md) の `:fill-pointer` で作成されたベクタのフィルポインタをデクリメントし、通過した要素 (最後にプッシュされた要素) を返します。フィルポインタのないベクタには `type-error` を通知します ([`fill-pointer`](fill-pointer.md))。フィルポインタがすでに 0 の場合は `simple-error` `VECTOR-POP: there is nothing left to pop` を通知します。

```lisp
(defparameter *v* (make-array 3 :fill-pointer 0))
(vector-push 10 *v*) ; => 0
(vector-push 20 *v*) ; => 1
(vector-pop *v*) ; => 20
(length *v*) ; => 1
```
