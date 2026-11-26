# position-if

`(position-if predicate sequence &key key start end from-end)`

`sequence` の要素のうち `predicate` を満たす最初の要素の 0 始まりのインデックスを返します。満たす要素がなければ `nil` を返します。シーケンスにはリストまたは文字列 (要素は文字) を渡せます。要素そのものではなく整数の位置を返します (`find-if` と比較してください)。`:key` は述語が見る値を選び、`:start`/`:end` は走査範囲を区切り、`:from-end` が真なら条件を満たす最後の要素のインデックスを返します。範囲外の境界 (負の数、整数でない値、長さを超える値、終端より後ろの開始位置) は、要素を調べる前に `type-error` を通知します。nil の `:end` は長さを意味します。

```lisp
(position-if #'evenp '(1 3 6 7)) ; => 2
```

```lisp
(position-if #'digit-char-p "ab3c") ; => 2
```

`:key` は述語に渡す値の選択関数、`:start`/`:end` は走査範囲、`:from-end` が真のときは条件を満たす最後の要素のインデックスを返します。
