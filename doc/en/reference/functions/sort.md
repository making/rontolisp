# sort

`(sort sequence predicate)`

Sorts `sequence` using `predicate`, a two-argument comparison function that returns non-nil when its first argument should precede its second. A list is sorted destructively: its cons cells are rearranged in place, so use the return value rather than the original variable. A vector, or a string the program built (not a literal), is likewise sorted in its own storage and comes back as the same object -- a fill-pointered or adjustable one keeps its fill pointer and its adjustable flag. A program-text string literal cannot be written in place, so sorting one returns a fresh string instead. Either way, use the return value rather than the original variable. The relative order of elements `predicate` considers equal is unspecified; use [`stable-sort`](stable-sort.md) when it matters.

```lisp
(sort (list 3 1 2) #'<) ; => (1 2 3)
```

```lisp
(sort "cab" #'char<) ; => "abc"
```

```lisp
(let ((v (make-array 3 :adjustable t :fill-pointer 3 :initial-contents '(3 1 2))))
  (let ((s (sort v #'<)))
    (list (fill-pointer s) (eq v s)))) ; => (3 T)
```
