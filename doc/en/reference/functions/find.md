# find

`(find item sequence)`

Returns the first element of `sequence` that is `eql` to `item`, or `nil` if no element matches. The sequence may be a list or a string; the elements of a string are characters. Unlike `member`, which returns the matching tail, `find` returns the element itself. Use `position` to obtain the index instead.

```lisp
(find 2 '(1 2 3)) ; => 2
```

```lisp
(find #\l "hello") ; => #\l
```
