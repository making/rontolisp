# incf

`(incf place [delta])`

`place` に格納された数値を `delta`（デフォルト `1`）だけ増やし、その結果を `place` に書き戻し、新しい値を返します。`place` には `setf` が受け付ける任意の場所を指定できます。`(setf place (+ place delta))` に展開されます。

```lisp
(let ((x 5)) (incf x 3)) ; => 8
```
