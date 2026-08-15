# wild-pathname-p

`(wild-pathname-p pathname &optional field-key)`

Whether the pathname carries a wildcard. With no `field-key` (or `nil`) it
answers true when ANY component is wild; with one of `:directory`, `:name` or
`:type` it tests just that component, and `:host` / `:device` / `:version` are
always `nil` because those components do not exist
([`pathname-host`](pathname-host.md)).

A component is wild when it holds a `*` (any run of characters) or a `?` (one
character) -- the same two wildcards [`directory`](directory.md) matches with, so
this predicate and that matcher cannot disagree.

```lisp
(list (wild-pathname-p "d/*.txt")
      (wild-pathname-p "d/a.txt")
      (wild-pathname-p "d/*.txt" :name)
      (wild-pathname-p "d/*.txt" :type)
      (wild-pathname-p "*/a.txt" :directory))   ; => (T NIL T NIL T)
```

An unknown field key signals.

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
