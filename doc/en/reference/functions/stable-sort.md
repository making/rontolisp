# stable-sort

`(stable-sort sequence predicate &key key)`

Sorts `sequence` like [`sort`](sort.md), but preserves the relative order of elements that `predicate` considers equal (neither `(predicate a b)` nor `(predicate b a)` is true). The optional `:key` function is applied to each element before comparison. A list, vector or string is sorted the same way `sort` sorts it: a list's cons cells are rearranged in place, and a vector or a non-literal string is sorted in its own storage and comes back as the same object (keeping a fill pointer and an adjustable flag, when present) -- a program-text string literal comes back as a fresh string instead. Use the return value rather than the original variable.

```lisp
(stable-sort '((1 . b) (0 . a) (1 . a)) #'< :key #'car) ; => ((0 . A) (1 . B) (1 . A))
```

```lisp
(stable-sort '(3 1 2) #'<) ; => (1 2 3)
```

```lisp
(let ((v (vector 3 1 2))) (let ((s (stable-sort v #'<))) (list s (eq v s)))) ; => (#(1 2 3) T)
```
