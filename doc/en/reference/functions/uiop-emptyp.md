# uiop:emptyp

`(uiop:emptyp x)`

Returns `t` when `x` is `nil` or a zero-length vector (a string included), `nil`
otherwise. UIOP's one-liner, kept verbatim from upstream:

```lisp
(defun uiop:emptyp (x)
  (or (null x) (and (vectorp x) (zerop (length x)))))
```

So it answers the "nothing here" question for the two shapes an empty value
takes, without deciding what a non-empty non-sequence means -- a number is
simply not empty.

```lisp
(list (uiop:emptyp nil) (uiop:emptyp "") (uiop:emptyp "ab"))   ; => (T T NIL)
```

## Backend support

Works on all four backends: it is a prelude definition written in rontolisp
itself and compiled into the program when used.
