# allocate-instance

`(allocate-instance class &rest initargs)`

`class` — クラスメタオブジェクト([`find-class`](find-class.md) / [`class-of`](class-of.md) の返り値)またはクラス名シンボル — の新しいインスタンスを、すべてのスロットが未束縛(unbound)の状態で返します。`:initform` は実行されず、`initialize-instance` も呼ばれません。これはオブジェクトマッパーが土台にする低レベルの割り当てステップで、割り当ててから各スロットを `(setf (slot-value ...))` で埋めます。書き込む前にスロットを読むと `unbound-slot` をシグナルします。`initargs` は Common Lisp と同様、受け取られますが無視されます(`allocate-instance` へのメソッドだけが参照するもので、静的サブセットではサポートされません)。割り当てられるのは `defclass` / `define-condition` のクラスのみで、組み込みクラスや `defstruct` のクラスはエラーをシグナルします。

```lisp
(defclass point () ((x :initarg :x :initform 0) (y :initarg :y)))
(let ((p (allocate-instance (find-class 'point))))
  (setf (slot-value p 'x) 10)
  (list (slot-boundp p 'y) (slot-value p 'x))) ; => (NIL 10)
```
