# position

`(position item sequence &key test test-not key start end from-end)`

Returns the 0-based index of the first element of `sequence` that matches `item`, or `nil` if no element matches. The comparison is `eql` by default; `:test` takes a function designator to use a different comparison and `:test-not` its negation, while `:key` takes a selector function applied to each element before the comparison. `:start`/`:end` bound the scanned region (the returned index still counts from the start of the whole sequence), and with `:from-end` true the LAST match wins. The sequence may be a list or a string; the elements of a string are characters. Unlike `find`, which returns the element, `position` returns its integer position.

```lisp
(position 3 '(1 2 3)) ; => 2
```

```lisp
(position #\space "hello world") ; => 5
```

```lisp
(position "b" '("a" "b" "c") :test #'string=) ; => 1
```

```lisp
(position #\, "a,b,c" :start 2) ; => 3
```

```lisp
(position 2 '(1 2 3 2 4) :from-end t) ; => 3
```
