# decf

`(decf place [delta])`

`place` に格納された数値を `delta` (デフォルトは `1`) だけ減算し、結果を `place` に書き戻して、新しい値を返します。`place` は `setf` が受け付ける任意の場所を指定できます。`(setf place (- place delta))` に展開されます。

```lisp
(let ((x 5)) (decf x)) ; => 4
```
