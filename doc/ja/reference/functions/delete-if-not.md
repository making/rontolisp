# delete-if-not

`(delete-if-not predicate list)`

`remove-if-not` の破壊的版です。`predicate` を満たす要素のみを残した `list` を返し、残りはその場で取り除きます。先頭が変わる可能性があるため、元の変数ではなく戻り値を使ってください。

```lisp
(delete-if-not #'evenp '(1 2 3 4)) ; => (2 4)
```
