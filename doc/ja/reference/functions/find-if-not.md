# find-if-not

`(find-if-not predicate sequence)`

`sequence` の要素のうち `predicate` を満たさ**ない**最初の要素を返します。すべての要素が満たす場合は `nil` を返します。シーケンスにはリストまたは文字列 (要素は文字) を渡せます。要素そのものを返します。`find-if` の補集合版です。

```lisp
(find-if-not #'evenp '(2 4 5 6)) ; => 5
```

```lisp
(find-if-not #'digit-char-p "12a3") ; => #\a
```
