# nsubstitute

`(nsubstitute new old list &key test key start end count from-end)`

`substitute` の破壊的対応版です。`list` の car をその場で書き換え、`old` に一致する要素をすべて `new` に置き換えます。ベクタや文字列にはその場で書き換えられる car がないため、`substitute` と同様に新しいシーケンスとして返ります。既定では `eql` で比較します。省略可能な `:test` キーワードに関数指定子を渡すと別の比較を使え、省略可能な `:key` キーワードに渡したセレクタ関数は比較の前に各要素へ適用されます。リスト構造が再利用されるため、変更は元の変数を通して見えます。`:start`/`:end` は走査する部分列を区切り (範囲外の要素はテストも処理もされません)、`:count` は処理する一致要素の個数を制限し、`:from-end` は要素を訪れる順序を逆にします。そのため `:count` と併用すると、後ろから数えた一致が対象になります。範囲外の境界 (負の数、整数でない値、長さを超える値、終端より後ろの開始位置) は、要素を調べる前に `type-error` を通知します。nil の `:end` は長さを意味します。整数でも nil でもない `:count` も、境界の検査より前に同様に `type-error` を通知します。負の `:count` は 0 として働き、nil は無制限です。

```lisp
(nsubstitute 0 2 '(1 2 3 2)) ; => (1 0 3 0)
```

```lisp
(nsubstitute 'x 2 (list '(1) '(2)) :key #'car) ; => ((1) X)
```

```lisp
(nsubstitute 9 1 (vector 1 2 1)) ; => #(9 2 9)
```

```lisp
(nsubstitute 0 2 (list 1 2 3 2) :count 1) ; => (1 0 3 2)
```
