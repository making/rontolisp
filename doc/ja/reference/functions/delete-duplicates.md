# delete-duplicates

`(delete-duplicates sequence &key test test-not key start end from-end)`

シーケンスから重複要素を除いて返します。`remove-duplicates` の破壊的版という位置づけですが、レンダリングは共有です(標準は呼び出し側に「結果を使う」ことを要求するため、非破壊の走査でも適合します。`sort` を `stable-sort` で実現しているのと同じ判断です)。既定では各要素の最後の出現が残り、`:from-end t` を指定すると最初の出現が残ります。比較は既定で `eql`、`:test` に比較関数指定子、`:test-not` に「偽のときに一致」する述語、`:key` に両辺へ適用するセレクタを渡せます。`:start` と `:end` は「比較の対象になる範囲」を区切ります。範囲外の要素はそのまま残り、比較されることもありません。シーケンスからはみ出す範囲 (負の数や整数でない境界、長さを超える境界、終端より後ろの開始位置) は、比較の前に `type-error` を通知します。nil の `:end` は長さを意味します。

```lisp
(delete-duplicates '(1 2 1 3 2)) ; => (1 3 2)
```

```lisp
(delete-duplicates '(1 2 1 3 2) :from-end t) ; => (1 2 3)
```

```lisp
(delete-duplicates '((1 . :a) (1 . :b) (2 . :c)) :key #'car :from-end t) ; => ((1 . :A) (2 . :C))
```

```lisp
(delete-duplicates '(0 1 2 3 1 2 3 9) :start 2 :end 6 :from-end t) ; => (0 1 2 3 1 3 9)
```
