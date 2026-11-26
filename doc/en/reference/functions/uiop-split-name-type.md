# uiop:split-name-type

`(uiop:split-name-type filename)`

Two values, the NAME and TYPE of a filename with no directory component: the
last dot separates them, except a lone leading dot, which belongs to the name
(the type is then `uiop:*unspecific-pathname-type*`, i.e. `nil`).

```lisp
(multiple-value-list (uiop:split-name-type "foo.lisp"))   ; => ("foo" "lisp")
```

```lisp
(multiple-value-list (uiop:split-name-type ".hidden"))   ; => (".hidden" NIL)
```

## Backend support

Works on all four backends (Lisp source, `uiop-pathname.lisp`).
