# notany

`(notany predicate &rest sequences)`

シーケンスのすべての要素 (組) で `predicate` が nil なら `t` を返し、満たすものが 1 つでもあれば `nil` を返します。`some` の補集合版です。各シーケンスにはリストまたは文字列 (要素は文字) を渡せます。シーケンスを複数渡した場合、述語はシーケンスごとに 1 つずつ引数を受け取り、最も短いシーケンスが尽きた時点で走査を終えます。空のシーケンスでは `t` を返します。

```lisp
(notany #'evenp '(1 3 5)) ; => T
```

```lisp
(notany #'digit-char-p "abc") ; => T
```

```lisp
(notany #'> '(1 2) '(3 4)) ; => T
```
