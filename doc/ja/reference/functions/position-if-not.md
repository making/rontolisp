# position-if-not

`(position-if-not predicate sequence &key key start end from-end)`

`predicate` を満たさない最初の要素の 0 始まりのインデックスを返します。すべての要素が満たす場合は `nil` を返します。`position-if` の補集合版で、同じキーワード引数を取ります。

```lisp
(position-if-not #'evenp '(2 4 5 6)) ; => 2
```

```lisp
(position-if-not #'digit-char-p "42a7") ; => 2
```
