# constantly

`(constantly value)`

Returns a function of any number of arguments that always answers `value`. The
idiomatic "accept everything" argument -- `uiop:collect-sub*directories` takes two
of them.

```lisp
(mapcar (constantly 7) '(a b c))   ; => (7 7 7)
```

## Backend support

All four backends -- one definition in rontolisp source, so unlike `complement`
(which expands into a wrapping lambda) it is a real function and `#'constantly` works.
