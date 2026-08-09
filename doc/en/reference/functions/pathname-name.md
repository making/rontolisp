# pathname-name

`(pathname-name pathname)`

The file-name component of a namestring, without its type: everything after the
last `/` and before the LAST dot. A dot at position 0 is part of the name rather
than a type separator, and a namestring that names no file -- one ending in `/`
-- answers `nil`.

Takes a pathname or a namestring; the split is pure string work on the
namestring -- nothing is
read from the filesystem and a nonexistent path answers just the same. It splits
by exactly the rule [`pathname-type`](pathname-type.md) uses for the other half,
and the one [`make-pathname`](make-pathname.md) defaults `:defaults` with, so the
three can never disagree.

```lisp
(pathname-name "db/migrations/20260101.up.sql")   ; => "20260101.up"
```

`(pathname-name "d/a")` is `"a"`, `(pathname-name "d/.a")` is `".a"`,
`(pathname-name "d/a.b.c")` is `"a.b"`, and `(pathname-name "d/")` is `NIL`.

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
