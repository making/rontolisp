# remove-if-not

`(remove-if-not predicate sequence &key key start end count from-end)`

`sequence` の要素のうち `predicate` を満たすものだけを残した新しいシーケンスを返します (満たさない要素が取り除かれます)。シーケンスにはリストまたは文字列を渡せます。文字列の場合は新しい文字列を返します。`remove-if` の補集合版です。元のシーケンスは変更されません。`:key` を渡すと述語はキー適用後の値を見ますが、残る要素は元の要素です。`:start`/`:end` は走査する部分列を区切り (範囲外の要素はテストも処理もされません)、`:count` は処理する一致要素の個数を制限し、`:from-end` は要素を訪れる順序を逆にします。そのため `:count` と併用すると、後ろから数えた一致が対象になります。範囲外の境界 (負の数、整数でない値、長さを超える値、終端より後ろの開始位置) は、要素を調べる前に `type-error` を通知します。nil の `:end` は長さを意味します。

```lisp
(remove-if-not #'evenp '(1 2 3 4)) ; => (2 4)
```

```lisp
(remove-if-not #'digit-char-p "a1b2") ; => "12"
```

```lisp
(remove-if-not #'evenp '((1 a) (2 b) (3 c)) :key #'car) ; => ((2 B))
```

```lisp
(remove-if-not #'evenp '(1 2 3 4) :count 1 :from-end t) ; => (1 2 4)
```
