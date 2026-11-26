# find-if-not

`(find-if-not predicate sequence &key key start end from-end)`

`sequence` の要素のうち `predicate` を満たさ**ない**最初の要素を返します。すべての要素が満たす場合は `nil` を返します。シーケンスにはリストまたは文字列 (要素は文字) を渡せます。要素そのものを返します。`find-if` の補集合版です。`:key` は述語が見る値を選び、`:start`/`:end` は走査範囲を区切り、`:from-end` が真なら該当する最後の要素を返します。範囲外の境界 (負の数、整数でない値、長さを超える値、終端より後ろの開始位置) は、要素を調べる前に `type-error` を通知します。nil の `:end` は長さを意味します。

```lisp
(find-if-not #'evenp '(2 4 5 6)) ; => 5
```

```lisp
(find-if-not #'digit-char-p "12a3") ; => #\a
```
