# slot-makunbound

`(slot-makunbound instance 'slot-name)`

指定した名前のスロットを未束縛にし、インスタンスを返します: [`slot-boundp`](slot-boundp.md) は `nil` を返すようになり、[`slot-value`](slot-value.md) やアクセサ経由の読み取りは `unbound-slot` をシグナルします。スロットへ格納すれば再び束縛されます。

スロット名は [`slot-value`](slot-value.md) と同様に、どのバックエンドでも実行時に計算されたシンボルで構いません。`#'slot-makunbound` は関数値として使えます。

```lisp
(defclass sm-point () ((x :initarg :x)))
(let ((p (make-instance 'sm-point :x 1)))
  (slot-makunbound p 'x)
  (handler-case (slot-value p 'x)
    (unbound-slot (e) (cell-error-name e)))) ; => X
```
