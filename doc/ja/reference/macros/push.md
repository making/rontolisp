# push

`(push item place)`

`place` に格納されたリストの先頭に `item` を追加し、長くなったリストを `place` に書き戻して、その新しいリストを返します。`place` には変数だけでなく、`setf` が受け付ける任意の場所を指定できます。`(setf place (cons item place))` に展開されます。

```lisp
(let ((s (list 1 2))) (push 0 s) s) ; => (0 1 2)
```
