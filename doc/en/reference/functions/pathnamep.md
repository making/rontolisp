# pathnamep

`(pathnamep object)`

Whether `object` is a pathname -- which here means: whether it is a string. A
rontolisp pathname IS its namestring, so a string is one and no other value can
be. It agrees with `(typep object 'pathname)`, as Common Lisp requires the two
to.

This deviates from Common Lisp, where a namestring is a pathname DESIGNATOR but
not a pathname. Here there is no other value that could be one, so the useful
answer and the standard's answer part ways.

In a `typecase` / `etypecase` the `pathname` clause YIELDS a string to any
sibling clause that could take it -- the catch-all, or a `string` / `vector` /
`array` / `sequence` clause. That is what keeps the two idioms apart: an
`(etypecase file (null ...) (pathname ...))` asking "is this a path?" gets the
pathname branch, while a `(typecase in (pathname (open in)) (t ...))`
discriminating a file from string CONTENT still sends the string to the
catch-all.

```lisp
(pathnamep "/tmp/data.json") ; => T
```

`(pathnamep 42)` is `NIL`.
