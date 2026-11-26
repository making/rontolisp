# substitute-if-not

`(substitute-if-not new predicate sequence &key key start end count from-end)`

[`substitute-if`](substitute-if.md) の補集合版です。述語が*満たさない*と判定したすべての要素を `new` に置き換えた新しいシーケンスを返します。同じ省略可能な `:key` セレクタを取り、シーケンスの種類を保ち、元のシーケンスは変更しません。破壊的な版は [`nsubstitute-if-not`](nsubstitute-if-not.md) です。`:start`/`:end` は走査する部分列を区切り (範囲外の要素はテストも処理もされません)、`:count` は処理する一致要素の個数を制限し、`:from-end` は要素を訪れる順序を逆にします。そのため `:count` と併用すると、後ろから数えた一致が対象になります。範囲外の境界 (負の数、整数でない値、長さを超える値、終端より後ろの開始位置) は、要素を調べる前に `type-error` を通知します。nil の `:end` は長さを意味します。整数でも nil でもない `:count` も、境界の検査より前に同様に `type-error` を通知します。負の `:count` は 0 として働き、nil は無制限です。

```lisp
(substitute-if-not 0 #'oddp '(1 2 3 4 5)) ; => (1 0 3 0 5)
```

```lisp
(substitute-if-not 'keep #'stringp '("a" 1 "b")) ; => ("a" KEEP "b")
```

```lisp
(substitute-if-not 0 #'oddp '(1 2 3 4) :count 1) ; => (1 0 3 4)
```
