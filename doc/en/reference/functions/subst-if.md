# subst-if

`(subst-if new predicate tree &key key)`

Like [`subst`](subst.md), but a subtree is replaced when `(funcall predicate subtree)` is true rather than when it matches an item. `:key` applies a selector to the subtree before the predicate sees it. Unchanged subtrees are shared with the original.

```lisp
(subst-if 0 #'numberp '(1 (2 x) 3)) ; => (0 (0 X) 0)
```
