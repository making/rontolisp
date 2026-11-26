# member-if-not

`(member-if-not predicate list &key key)`

The tail of `list` starting at the first element the predicate REJECTS, or nil -- [`member-if`](member-if.md) over the negated predicate. `:key` applies a selector to each element before the predicate sees it. Deprecated by the standard.

```lisp
(member-if-not #'numberp '(1 2 a b)) ; => (A B)
```

```lisp
(member-if-not #'evenp '(1 3 4 5) :key #'1+) ; => (4 5)
```
