# pop

`(pop place)`

`place` に格納されたリストから先頭要素を取り除きます。その要素を返し、リストの残り（その `cdr`）を `place` に書き戻します。`place` には `setf` が受け付ける任意の場所を指定できます。nil に対して `pop` を呼び出すと nil を返し、place は nil のまま残ります。

```lisp
(let ((s (list 1 2 3))) (pop s)) ; => 1
```
