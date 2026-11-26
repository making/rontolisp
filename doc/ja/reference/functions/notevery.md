# notevery

`(notevery predicate &rest sequences)`

シーケンスの少なくとも 1 つの要素 (組) で `predicate` が nil なら `t` を返し、すべてが満たす場合は `nil` を返します。`every` の補集合版です。各シーケンスにはリストまたは文字列 (要素は文字) を渡せます。シーケンスを複数渡した場合、述語はシーケンスごとに 1 つずつ引数を受け取り、最も短いシーケンスが尽きた時点で走査を終えます。空のシーケンスでは `nil` を返します。

```lisp
(notevery #'evenp '(2 4 5)) ; => T
```

```lisp
(notevery #'digit-char-p "12a") ; => T
```

```lisp
(notevery #'< '(1 2) '(3 0)) ; => T
```
