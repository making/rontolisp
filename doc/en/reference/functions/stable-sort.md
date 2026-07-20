# stable-sort

`(stable-sort sequence predicate &key key)`

Sorts `sequence` like [`sort`](sort.md), but preserves the relative order of elements that `predicate` considers equal (neither `(predicate a b)` nor `(predicate b a)` is true). The optional `:key` function is applied to each element before comparison. Unlike Common Lisp's destructive `stable-sort`, the result is always a fresh list — the argument is not modified, and a string or vector argument also comes back as a list of its elements.

```lisp
(stable-sort '((1 . b) (0 . a) (1 . a)) #'< :key #'car) ; => ((0 . A) (1 . B) (1 . A))
```

```lisp
(stable-sort '(3 1 2) #'<) ; => (1 2 3)
```
