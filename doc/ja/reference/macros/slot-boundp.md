# slot-boundp

`(slot-boundp instance 'slot-name)`

インスタンスの指定した名前のスロットが値を保持しているかどうかを返します: インスタンスのクラスがそのスロットを持たない場合、`:initform` も指定されずに書かれ initarg も与えられなかった場合、または [`slot-makunbound`](slot-makunbound.md) の後は `nil`、それ以外は `t` です。未束縛のスロットを [`slot-value`](slot-value.md) やアクセサで読むと `unbound-slot` がシグナルされます。その [`cell-error-name`](../functions/cell-error-name.md) はスロット名、[`unbound-slot-instance`](../functions/unbound-slot-instance.md) はオブジェクトです。

JVM / WASM コンパイラではスロット名はクオートされたシンボルリテラルである必要があります ([`slot-value`](slot-value.md) と同様)。実行時に計算されるスロット名はインタープリタのみで動作します。

```lisp
(defclass sb-point () ((x :initarg :x) (y :initform 0)))
(let ((p (make-instance 'sb-point)))
  (list (slot-boundp p 'x) (slot-boundp p 'y))) ; => (NIL T)
```
