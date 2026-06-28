# position

`(position item list)`

Returns the 0-based index of the first element of `list` that is `eql` to `item`, or `nil` if no element matches. Unlike `find`, which returns the element, `position` returns its integer position.

```lisp
(position 3 '(1 2 3)) ; => 2
```
