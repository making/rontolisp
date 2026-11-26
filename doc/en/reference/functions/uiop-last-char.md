# uiop:last-char

`(uiop:last-char s)`

Returns the last character of the string `s`, or `nil` when `s` is empty or is
not a string -- [`uiop:first-char`](uiop-first-char.md)'s mirror image, kept
verbatim from upstream UIOP.

```lisp
(list (uiop:last-char "hello") (uiop:last-char ""))   ; => (#\o NIL)
```

## Backend support

Works on all four backends: it is a uiop library definition written in rontolisp
itself and compiled into the program when used.
