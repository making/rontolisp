# make-list

`(make-list size &key initial-element)`

すべてが `initial-element`(既定は `nil`)である `size` 個の要素からなる、新たに割り当てられた真正リストを返します。`size` が `0` の場合は空リストになります。要素のフォームは **1 回だけ**評価され、すべてのセルがその 1 つの値を共有します(Common Lisp の規定どおり)。したがって可変な要素を指定した場合、全セルが同一のオブジェクトになります。それ以外のキーワードはエラーです。

```lisp
(make-list 3) ; => (NIL NIL NIL)
```

```lisp
(make-list 3 :initial-element 0) ; => (0 0 0)
```
