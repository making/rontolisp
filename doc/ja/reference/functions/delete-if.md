# delete-if

`(delete-if predicate list)`

`remove-if` の破壊的版です。`predicate` を満たすすべての要素をその場で取り除いた `list` を返します。ベクタや文字列にはその場で変更できるコンスセルがないため、`remove-if` と同様に新しいシーケンスとして返ります。先頭が変わる可能性があるため、元の変数ではなく戻り値を使ってください。

```lisp
(delete-if #'evenp '(1 2 3 4)) ; => (1 3)
```

```lisp
(delete-if #'oddp (vector 1 2 3 4)) ; => #(2 4)
```
