# pathname-directory

`(pathname-directory pathname)`

The directory component of a namestring, as Common Lisp's list: `:absolute` or
`:relative` followed by one component per directory level. A namestring with no
directory part answers `nil`.

It is component-for-component the INVERSE of what
[`make-pathname`](make-pathname.md) builds, so the special levels come back as
the keywords they were given as: `..` is `:up`, `*` is `:wild` and `**` is
`:wild-inferiors`.

Takes a pathname or a namestring; the split is pure string work on the
namestring -- nothing is
read from the filesystem and a nonexistent path answers just the same. It pairs
with [`directory`](directory.md): a walk uses it to decide what to do with each
entry it was handed.

```lisp
(pathname-directory "/usr/share/zoneinfo/Asia/Tokyo")   ; => (:ABSOLUTE "usr" "share" "zoneinfo" "Asia")
```

`(pathname-directory "a/b/c.txt")` is `(:RELATIVE "a" "b")`,
`(pathname-directory "c.txt")` is `NIL`, `(pathname-directory "/")` is
`(:ABSOLUTE)`, and `(pathname-directory "/a/**/x.lisp")` is
`(:ABSOLUTE "a" :WILD-INFERIORS)`.

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
