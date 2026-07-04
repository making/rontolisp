# position

`(position item sequence &key test key)`

Returns the 0-based index of the first element of `sequence` that matches `item`, or `nil` if no element matches. The comparison is `eql` by default; the optional `:test` keyword takes a function designator to use a different comparison, and the optional `:key` keyword takes a selector function applied to each element before the comparison. The sequence may be a list or a string; the elements of a string are characters. Unlike `find`, which returns the element, `position` returns its integer position.

```lisp
(position 3 '(1 2 3)) ; => 2
```

```lisp
(position #\space "hello world") ; => 5
```

```lisp
(position "b" '("a" "b" "c") :test #'string=) ; => 1
```
