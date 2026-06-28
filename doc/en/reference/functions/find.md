# find

`(find item list)`

Returns the first element of `list` that is `eql` to `item`, or `nil` if no element matches. Unlike `member`, which returns the matching tail, `find` returns the element itself. Use `position` to obtain the index instead.

```lisp
(find 2 '(1 2 3)) ; => 2
```
