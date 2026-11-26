# merge-pathnames

`(merge-pathnames pathname &optional defaults)`

Fills the gaps in `pathname` from `defaults` and returns the merged pathname.
Both arguments take either spelling (a pathname or a namestring), and the rule
works on the two parts a namestring has: the *directory* (everything through the last `/`) and the
*file* (what follows it). The directory of `pathname` wins when it is absolute,
is appended to `defaults`' directory when it is relative, and is taken from
`defaults` when `pathname` has none; the file of `pathname` wins unless it is
empty. Omitting `defaults` merges against the working directory, which leaves
`pathname` unchanged.

This is how a library names a file relative to a directory it computed earlier
-- a data file next to its own sources, say. `uiop:merge-pathnames*` is the
ASDF/UIOP spelling of the same merge.

```lisp
(merge-pathnames "zoneinfo/" "/opt/local-time/")   ; => #P"/opt/local-time/zoneinfo/"
```

## Backend support

Works on all four backends: one definition in rontolisp source, spliced into the
program when it is referenced.
