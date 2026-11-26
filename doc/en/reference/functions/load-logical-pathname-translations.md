# load-logical-pathname-translations

`(load-logical-pathname-translations host)`

`nil` when the host is already defined through
[`logical-pathname-translations`](logical-pathname-translations.md). There is no
translations file to search, so an undefined host signals a `file-error`.

```lisp
(setf (logical-pathname-translations "APP") '(("**;*.*.*" "/opt/app/**/*.*")))
(load-logical-pathname-translations "APP")
; => NIL
```

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
