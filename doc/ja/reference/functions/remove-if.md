# remove-if

`(remove-if predicate sequence)`

`sequence` の要素のうち `predicate` を満たさ**ない**ものからなる新しいシーケンスを返します (満たす要素が取り除かれます)。シーケンスにはリストまたは文字列を渡せます。文字列の場合は新しい文字列を返します。元のシーケンスは変更されません。破壊的な操作にはリスト専用の `delete-if` を使います。

```lisp
(remove-if #'evenp '(1 2 3 4)) ; => (1 3)
```

```lisp
(remove-if #'digit-char-p "a1b2") ; => "ab"
```
