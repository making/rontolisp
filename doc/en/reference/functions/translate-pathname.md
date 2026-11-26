# translate-pathname

`(translate-pathname source from-wildcard to-wildcard &key)`

Matches `source` against `from-wildcard`, then substitutes the pieces its
wildcards captured into the wildcards of `to-wildcard`. The wildcards are the
ones the rest of the pathname family understands: `*` (any run of characters), `?`
(one character) and `**/` (zero or more whole directory levels). A `source` that
does not match `from-wildcard` signals, as it does in Common Lisp.

```lisp
(list (namestring (translate-pathname "src/foo.lisp" "src/*.lisp" "build/*.fasl"))
      (namestring (translate-pathname "a/b.c" "*/*.*" "x/*-y.*")))
; => ("build/foo.fasl" "x/b-y.c")
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

Lite: the match and the substitution are component-wise -- a plain `*` or `?`
never crosses a directory boundary, and a `**/` captures a run of whole
directory levels -- but three diagnostics a structured implementation makes are
not reproduced: a wildcard in `to-wildcard` with no capture left substitutes the
empty string instead of signalling that FROM has too few wildcards for TO,
wildcards standing next to each other in one component consume one capture
apiece, and an unpaired `**` is not checked.

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
