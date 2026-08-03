# delete-duplicates

`(delete-duplicates sequence &key test key from-end)`

シーケンスから重複要素を除いて返します。`remove-duplicates` の破壊的版という位置づけですが、レンダリングは共有です(標準は呼び出し側に「結果を使う」ことを要求するため、非破壊の走査でも適合します。`sort` を `stable-sort` で実現しているのと同じ判断です)。既定では各要素の最後の出現が残り、`:from-end t` を指定すると最初の出現が残ります。比較は既定で `eql`、`:test` に比較関数指定子、`:key` に両辺へ適用するセレクタを渡せます。`:from-end` はリテラルの `t` または `nil` である必要があります。

```lisp
(delete-duplicates '(1 2 1 3 2)) ; => (1 3 2)
```

```lisp
(delete-duplicates '(1 2 1 3 2) :from-end t) ; => (1 2 3)
```

```lisp
(delete-duplicates '((1 . :a) (1 . :b) (2 . :c)) :key #'car :from-end t) ; => ((1 . :A) (2 . :C))
```
