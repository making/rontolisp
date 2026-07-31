# uiop:first-char

`(uiop:first-char s)`

Returns the first character of the string `s`, or `nil` when `s` is empty or is
not a string. UIOP's one-liner, kept verbatim from upstream, so the "is there a
first character" test and the access are one call rather than a `length` guard
plus a `char`.

```lisp
(list (uiop:first-char "hello") (uiop:first-char ""))   ; => (#\h NIL)
```

The mirror image is [`uiop:last-char`](uiop-last-char.md); quri's `render-uri`
calls both (plus [`uiop:emptyp`](uiop-emptyp.md)) to decide whether a path needs
a leading slash.

## Backend support

Works on all four backends: it is a prelude definition written in rontolisp
itself and compiled into the program when used.
