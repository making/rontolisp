# subst-if-not

`(subst-if-not new predicate tree &key key)`

述語を否定した [`subst-if`](subst-if.md)。述語が偽を返した部分木が置換されます。規格では非推奨です。

```lisp
(subst-if-not 0 #'listp '(1 (2))) ; => (0 (0))
```
