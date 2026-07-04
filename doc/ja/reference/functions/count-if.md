# count-if

`(count-if predicate sequence)`

`sequence` の要素のうち `predicate` を満たすものの個数を返します。シーケンスにはリストまたは文字列 (要素は文字) を渡せます。`count` の述語ベース版です。

```lisp
(count-if #'evenp '(1 2 3 4)) ; => 2
```

```lisp
(count-if #'digit-char-p "a1b2") ; => 2
```
