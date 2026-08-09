# pathname-type

`(pathname-type pathname)`

The type (extension) component of a namestring, without its dot -- what follows
the LAST dot of the file part -- or `nil` when there is none. A dot at position 0
does not separate a type, so a dotfile has a name and no type.

Takes a pathname or a namestring; the split is pure string work on the
namestring -- nothing is
read from the filesystem and a nonexistent path answers just the same. It is the
other half of the split [`pathname-name`](pathname-name.md) takes, by the same
rule.

```lisp
(pathname-type "db/migrations/20260101.up.sql")   ; => "sql"
```

`(pathname-type "d/a")` is `NIL`, `(pathname-type "d/.a")` is `NIL`, and
`(pathname-type "d/a.b.c")` is `"c"`.

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
