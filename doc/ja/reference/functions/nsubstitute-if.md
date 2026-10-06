# nsubstitute-if

`(nsubstitute-if new predicate list &key key start end count from-end)`

[`substitute-if`](substitute-if.md) の破壊的な版です。述語を満たす要素を持つコンスの `car` をその場で書き換え、(変更されうる) 元のリストを返します。コンスセルを再利用するため、そのリストへの他の参照も変更を観測します。ベクタや文字列にはその場で書き換えられるコンスセルがないため、`substitute-if` と同様に新しいシーケンスとして返ります。`:start`/`:end` は走査する部分列を区切り (範囲外の要素はテストも処理もされません)、`:count` は処理する一致要素の個数を制限し、`:from-end` は要素を訪れる順序を逆にします。そのため `:count` と併用すると、後ろから数えた一致が対象になります。範囲外の境界 (負の数、整数でない値、長さを超える値、終端より後ろの開始位置) は、要素を調べる前に `type-error` を通知します。nil の `:end` は長さを意味します。整数でも nil でもない `:count` も、境界の検査より前に同様に `type-error` を通知します。負の `:count` は 0 として働き、nil は無制限です。

```lisp
(nsubstitute-if 0 #'oddp (list 1 2 3 4 5)) ; => (0 2 0 4 0)
```

```lisp
(let* ((a (list 1 2 3)) (b a)) (nsubstitute-if 0 #'oddp a) b) ; => (0 2 0)
```

```lisp
(nsubstitute-if 0 #'oddp (vector 1 2 3)) ; => #(0 2 0)
```

```lisp
(nsubstitute-if 0 #'oddp (list 1 2 3) :count 1 :from-end t) ; => (1 2 0)
```
