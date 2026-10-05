# count-if-not

`(count-if-not predicate sequence &key key start end from-end)`

`predicate` を満たさ**ない** `sequence` の要素数を返します(`count-if` の補集合)。シーケンスはリスト・ベクタ・文字列のいずれでも構いません。`:key` は述語に渡す値を選びます。`:start`/`:end` は走査する部分列を区切り、`:from-end` は要素を訪れる順序を逆にします。個数自体は変わりませんが、副作用のある `:key` や `:test` は逆順で呼ばれます。範囲外の境界 (負の数、整数でない値、長さを超える値、終端より後ろの開始位置) は、要素を調べる前に `type-error` を通知します。nil の `:end` は長さを意味します。

```lisp
(count-if-not #'evenp '(1 2 3 4 5)) ; => 3
```

```lisp
(count-if-not #'alpha-char-p "ab1c2") ; => 2
```

```lisp
(count-if-not #'oddp '((1) (2) (3)) :key #'car) ; => 1
```

```lisp
(count-if-not #'evenp '(1 2 3 4) :start 2) ; => 1
```
