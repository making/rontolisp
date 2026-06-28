# find-if

`(find-if predicate list)`

Returns the first element of `list` that satisfies `predicate`, or `nil` if none does. It returns the element itself, not its index or tail. Use `find-if-not` for the complementary search.

```lisp
(find-if #'evenp '(1 3 6 7)) ; => 6
```
