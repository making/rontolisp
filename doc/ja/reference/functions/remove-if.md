# remove-if

`(remove-if predicate sequence &key key)`

`sequence` の要素のうち `predicate` を満たさ**ない**ものからなる新しいシーケンスを返します (満たす要素が取り除かれます)。シーケンスにはリストまたは文字列を渡せます。文字列の場合は新しい文字列を返します。元のシーケンスは変更されません。破壊的な操作にはリスト専用の `delete-if` を使います。`:key` を渡すと述語はキー適用後の値を見ますが、残る要素は元の要素です。

```lisp
(remove-if #'evenp '(1 2 3 4)) ; => (1 3)
```

```lisp
(remove-if #'digit-char-p "a1b2") ; => "ab"
```

```lisp
(remove-if #'evenp '((1 a) (2 b) (3 c)) :key #'car) ; => ((1 A) (3 C))
```
