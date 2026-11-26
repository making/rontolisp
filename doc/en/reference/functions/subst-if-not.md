# subst-if-not

`(subst-if-not new predicate tree &key key)`

[`subst-if`](subst-if.md) over the negated predicate: a subtree is replaced when the predicate REJECTS it. Deprecated by the standard.

```lisp
(subst-if-not 0 #'listp '(1 (2))) ; => (0 (0))
```
