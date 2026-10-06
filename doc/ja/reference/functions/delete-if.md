# delete-if

`(delete-if predicate list &key key start end count from-end)`

`remove-if` の破壊的版です。`predicate` を満たすすべての要素をその場で取り除いた `list` を返します。ベクタや文字列にはその場で変更できるコンスセルがないため、`remove-if` と同様に新しいシーケンスとして返ります。先頭が変わる可能性があるため、元の変数ではなく戻り値を使ってください。`:start`/`:end` は走査する部分列を区切り (範囲外の要素はテストも処理もされません)、`:count` は処理する一致要素の個数を制限し、`:from-end` は要素を訪れる順序を逆にします。そのため `:count` と併用すると、後ろから数えた一致が対象になります。範囲外の境界 (負の数、整数でない値、長さを超える値、終端より後ろの開始位置) は、要素を調べる前に `type-error` を通知します。nil の `:end` は長さを意味します。整数でも nil でもない `:count` も、境界の検査より前に同様に `type-error` を通知します。負の `:count` は 0 として働き、nil は無制限です。

```lisp
(delete-if #'evenp '(1 2 3 4)) ; => (1 3)
```

```lisp
(delete-if #'oddp (vector 1 2 3 4)) ; => #(2 4)
```

```lisp
(delete-if #'evenp (list 1 2 3 4) :count 1 :from-end t) ; => (1 2 3)
```
