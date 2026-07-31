# pathname-directory

`(pathname-directory pathname)`

The directory component of a namestring, as Common Lisp's list: `:absolute` or
`:relative` followed by one string per directory level. A namestring with no
directory part answers `nil`.

A rontolisp pathname IS its namestring, so this is pure string work -- nothing is
read from the filesystem and a nonexistent path answers just the same. It pairs
with [`directory`](directory.md): a walk uses it to decide what to do with each
entry it was handed.

```lisp
(pathname-directory "/usr/share/zoneinfo/Asia/Tokyo")   ; => (:ABSOLUTE "usr" "share" "zoneinfo" "Asia")
```

`(pathname-directory "a/b/c.txt")` is `(:RELATIVE "a" "b")`,
`(pathname-directory "c.txt")` is `NIL`, and `(pathname-directory "/")` is
`(:ABSOLUTE)`.

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
