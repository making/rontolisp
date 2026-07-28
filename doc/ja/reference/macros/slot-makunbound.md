# slot-makunbound

`(slot-makunbound instance 'slot-name)`

指定した名前のスロットを未束縛にし、インスタンスを返します: [`slot-boundp`](slot-boundp.md) は `nil` を返すようになり、[`slot-value`](slot-value.md) やアクセサ経由の読み取りは `unbound-slot` をシグナルします。スロットへ格納すれば再び束縛されます。

JVM / WASM コンパイラではスロット名はクオートされたシンボルリテラルである必要があります ([`slot-value`](slot-value.md) と同様)。実行時に計算されるスロット名はインタープリタのみで動作します。

```lisp
(defclass sm-point () ((x :initarg :x)))
(let ((p (make-instance 'sm-point :x 1)))
  (slot-makunbound p 'x)
  (handler-case (slot-value p 'x)
    (unbound-slot (e) (cell-error-name e)))) ; => X
```
