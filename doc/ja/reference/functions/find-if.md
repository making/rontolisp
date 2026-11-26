# find-if

`(find-if predicate sequence &key key start end from-end)`

`sequence` の要素のうち `predicate` を満たす最初の要素を返します。満たす要素がなければ `nil` を返します。シーケンスにはリストまたは文字列 (要素は文字) を渡せます。インデックスや末尾ではなく要素そのものを返します。補集合の探索には `find-if-not` を使います。`:key` は述語が見る値を選び、`:start`/`:end` は走査範囲を区切り、`:from-end` が真なら該当する最後の要素を返します。範囲外の境界 (負の数、整数でない値、長さを超える値、終端より後ろの開始位置) は、要素を調べる前に `type-error` を通知します。nil の `:end` は長さを意味します。

```lisp
(find-if #'evenp '(1 3 6 7)) ; => 6
```

```lisp
(find-if #'digit-char-p "ab3c") ; => #\3
```
