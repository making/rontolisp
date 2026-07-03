# position

`(position item sequence)`

Returns the 0-based index of the first element of `sequence` that is `eql` to `item`, or `nil` if no element matches. The sequence may be a list or a string; the elements of a string are characters. Unlike `find`, which returns the element, `position` returns its integer position.

```lisp
(position 3 '(1 2 3)) ; => 2
```

```lisp
(position #\space "hello world") ; => 5
```
