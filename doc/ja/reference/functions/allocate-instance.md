# allocate-instance

`(allocate-instance class &rest initargs)`

`class` — クラスメタオブジェクト([`find-class`](find-class.md) / [`class-of`](class-of.md) の返り値)またはクラス名シンボル — の新しいインスタンスを、すべてのスロットが未束縛(unbound)の状態で返します。`:initform` は実行されず、`initialize-instance` も呼ばれません。これはオブジェクトマッパーが土台にする低レベルの割り当てステップで、割り当ててから各スロットを `(setf (slot-value ...))` で埋めます。書き込む前にスロットを読むと `unbound-slot` をシグナルします。`initargs` は Common Lisp と同様、受け取られますが無視されます。参照するのは `allocate-instance` へのメソッドだけです。メソッドがあっても他のクラスにはシステムのものが働き、`call-next-method` からも呼べます。`:metaclass` で定義したクラスの [`make-instance`](../macros/make-instance.md) は、クラスメタオブジェクトと initarg を渡して `allocate-instance` を呼び、その答えのスロットを埋めます(`standard-class` 自体や、あるクラスの `eql` へのメソッドは、`:metaclass` のないクラスの `make-instance` からは呼ばれません)。割り当てられるのは `defclass` / `define-condition` のクラスと、メタクラスの `make-instance` で実行時に作ったクラスメタオブジェクト([`defclass`](../special-forms/defclass.md) を参照。インスタンスはスロットを持ちません)のみで、組み込みクラスや `defstruct` のクラスはエラーをシグナルします。

```lisp
(defclass point () ((x :initarg :x :initform 0) (y :initarg :y)))
(let ((p (allocate-instance (find-class 'point))))
  (setf (slot-value p 'x) 10)
  (list (slot-boundp p 'y) (slot-value p 'x))) ; => (NIL 10)
```
