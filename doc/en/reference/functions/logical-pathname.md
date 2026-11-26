# logical-pathname

`(logical-pathname pathspec)`

The pathname of a namestring whose host is defined through
[`logical-pathname-translations`](logical-pathname-translations.md). Any other
argument signals a `type-error`, as Common Lisp requires; no host, `SYS`
included, is predefined.

```lisp
(setf (logical-pathname-translations "APP") '(("**;*.*.*" "/opt/app/**/*.*")))
(namestring (logical-pathname "APP:SRC;FOO.LISP"))
; => "APP:SRC;FOO.LISP"
```

The answer is an ordinary pathname value carrying the namestring: there is no
separate logical-pathname type.
[`translate-logical-pathname`](translate-logical-pathname.md) maps it to the
physical pathname.

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
