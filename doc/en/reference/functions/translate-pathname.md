# translate-pathname

`(translate-pathname source from-wildcard to-wildcard &key)`

Matches `source` against `from-wildcard`, then substitutes the pieces its
wildcards captured into `to-wildcard`, left to right. The wildcards are the ones
the rest of the pathname family understands: `*` (any run of characters) and `?`
(one character). A `source` that does not match `from-wildcard` signals, as it
does in Common Lisp.

```lisp
(list (namestring (translate-pathname "src/foo.lisp" "src/*.lisp" "build/*.fasl"))
      (namestring (translate-pathname "a/b.c" "*/*.*" "x/*-y.*")))
; => ("build/foo.fasl" "x/a-y.b")
```

Lite: matching runs over the FLAT namestring, so a `*` may span a `/` where a
structured implementation would stop at a directory boundary.

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
