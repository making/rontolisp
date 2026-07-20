# assoc-if

`(assoc-if predicate alist)`

Searches an association list and returns the first pair whose car satisfies `predicate`, or `nil` if none does. This is the predicate-based counterpart of `assoc`.

```lisp
(assoc-if #'oddp '((2 a) (3 b))) ; => (3 B)
```
