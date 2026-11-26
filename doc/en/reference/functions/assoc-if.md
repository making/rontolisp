# assoc-if

`(assoc-if predicate alist &key key)`

Searches an association list and returns the first pair whose car satisfies `predicate`, or `nil` if none does. `:key` applies a selector to the car before the predicate sees it. An `alist` that is not a list, or a dotted one the search reaches the end of, signals a `type-error`. This is the predicate-based counterpart of `assoc`; [`assoc-if-not`](assoc-if-not.md) stops at the first car the predicate rejects.

```lisp
(assoc-if #'oddp '((2 a) (3 b))) ; => (3 B)
```

```lisp
(assoc-if #'oddp '((1 . a) (2 . b)) :key #'1+) ; => (2 . B)
```
