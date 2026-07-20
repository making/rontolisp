# make-list

`(make-list size)`

すべてが `nil` である `size` 個の要素からなる、新たに割り当てられた真正リストを返します。完全な Common Lisp の `:initial-element` キーワードはサポートされていないため、充填値は常に `nil` です。`size` が `0` の場合は空リストになります。

```lisp
(make-list 3) ; => (NIL NIL NIL)
```
