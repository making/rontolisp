# parse-namestring

`(parse-namestring thing &optional host defaults)`

Parses a namestring into a pathname, returning it and the position parsing
stopped at as a second value. Lite: a rontolisp namestring has no host
component to parse against, so the whole string is the namestring, the second
value is its length, and `host`/`defaults` are accepted and ignored. A pathname
argument answers itself.

```lisp
(parse-namestring "d/a.txt")   ; => #P"d/a.txt"
```

`(multiple-value-list (parse-namestring "d/a.txt"))` is `(#P"d/a.txt" 7)`.

## Backend support

All four backends -- one definition in rontolisp source, spliced into the
program when it is referenced.
