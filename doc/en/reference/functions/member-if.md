# member-if

`(member-if predicate list)`

Searches `list` for the first element that satisfies `predicate` and returns the sublist (tail) starting at that element, or `nil` if none does. The returned tail shares structure with the original list. Use `member` to search by item value instead of by predicate.

```lisp
(member-if #'oddp '(2 4 5 6)) ; => (5 6)
```
