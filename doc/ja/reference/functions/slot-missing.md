# slot-missing

`(slot-missing class object slot-name operation &optional new-value)`

プログラム中のどのクラスも宣言していないスロット名へ [`slot-value`](../macros/slot-value.md) でアクセスしたときに呼ばれる総称関数です。読み出しは操作 `slot-value` を渡し、メソッドの返す値を返します。`setf` は `setf` と新しい値を渡し、新しい値を返します。システムメソッドはエラーをシグナルするので、どのメソッドも扱わないアクセスはメソッドがない場合と同じくシグナルします。

```lisp
(defclass sm-bag () ())
(defmethod slot-missing (class (o sm-bag) name operation &optional new-value)
  (list operation name new-value))
(list (slot-value (make-instance 'sm-bag) 'color)
      (setf (slot-value (make-instance 'sm-bag) 'color) :red))
; => ((SLOT-VALUE COLOR NIL) :RED)
```

Lite: そのようなスロットへの [`slot-boundp`](../macros/slot-boundp.md) はこれを呼ばずに `nil` を返します。また、別のクラスが宣言しているスロットは欠落として検出されません。
