# assoc-if-not

`(assoc-if-not predicate alist &key key)`

The first pair of `alist` whose car the predicate REJECTS, or nil -- [`assoc-if`](assoc-if.md) over the negated predicate. `:key` applies a selector to the car before the predicate sees it. Deprecated by the standard.

```lisp
(assoc-if-not #'numberp '((1 . a) (b . c))) ; => (B . C)
```

```lisp
(assoc-if-not #'oddp '((1 . a) (2 . b)) :key #'1+) ; => (1 . A)
```
