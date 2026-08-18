# translate-pathname

`(translate-pathname source from-wildcard to-wildcard &key)`

Matches `source` against `from-wildcard`, then substitutes the pieces its
wildcards captured into `to-wildcard`, left to right. The wildcards are the ones
the rest of the pathname family understands: `*` (any run of characters), `?`
(one character) and `**/` (zero or more whole directory levels). A `source` that
does not match `from-wildcard` signals, as it does in Common Lisp.

```lisp
(list (namestring (translate-pathname "src/foo.lisp" "src/*.lisp" "build/*.fasl"))
      (namestring (translate-pathname "a/b.c" "*/*.*" "x/*-y.*")))
; => ("build/foo.fasl" "x/a-y.b")
```

A `**/` is ONE wildcard, separator included: it captures the whole run of
directory levels it consumed, and a `**/` in `to-wildcard` writes that run back
verbatim. Because it matches zero levels as well as many, a source with no
intervening directory still translates:

```lisp
(list (namestring (translate-pathname "/a/b/d/c.lisp" "/a/**/*.lisp" "/x/**/*.fasl"))
      (namestring (translate-pathname "/a/c.lisp" "/a/**/*.lisp" "/x/**/*.fasl")))
; => ("/x/b/d/c.fasl" "/x/c.fasl")
```

Lite: matching runs over the FLAT namestring and captures are substituted
POSITIONALLY rather than component by component, so a plain `*` may span a `/`
where a structured implementation would stop at a directory boundary, and a
`to-wildcard` holding fewer wildcards than `from-wildcard` consumes the first of
them rather than the matching component.

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
