# pathnamep

`(pathnamep object)`

Whether `object` is a pathname -- the distinct value `#P"..."` denotes, an
object carrying its namestring. A string is NOT one (it merely DESIGNATES a
pathname, as in standard Common Lisp), and neither is any other value. It
agrees with `(typep object 'pathname)`, as Common Lisp requires the two to.

The producers -- `pathname`, `make-pathname`, `merge-pathnames`, `probe-file`,
`truename`, `directory` and the `uiop:` directory walkers -- all answer
pathnames, and every path-taking operator accepts a pathname and a namestring
alike, so the predicate is what lets a library tell a FILE from TEXT:
`(typecase in (pathname (open in)) (t ...))` opens a `#P"..."` argument and
parses a string argument as content.

```lisp
(pathnamep #P"/tmp/data.json") ; => T
```

`(pathnamep "/tmp/data.json")` and `(pathnamep 42)` are `NIL`.
