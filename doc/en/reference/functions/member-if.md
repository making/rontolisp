# member-if

`(member-if predicate list &key key)`

Searches `list` for the first element that satisfies `predicate` and returns the sublist (tail) starting at that element, or `nil` if none does. `:key` applies a selector to each element before the predicate sees it. The returned tail shares structure with the original list. A `list` that is not a list, or a dotted list the search reaches the end of, signals a `type-error`. Use `member` to search by item value instead of by predicate, and [`member-if-not`](member-if-not.md) to stop at the first element the predicate rejects.

```lisp
(member-if #'oddp '(2 4 5 6)) ; => (5 6)
```

```lisp
(member-if #'oddp '(1 2 3) :key #'1+) ; => (2 3)
```
