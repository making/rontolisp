# uiop:pathname-parent-directory-pathname

`(uiop:pathname-parent-directory-pathname pathname)`

One directory level up from the pathname's directory:
`/foo/bar/baz/file.type` answers `#P"/foo/bar/"`. The root's parent is the
root, and the parent of a single-level relative directory is the empty
pathname.

```lisp
(uiop:pathname-parent-directory-pathname #P"/a/b/c.txt")   ; => #P"/a/"
```

## Backend support

Works on all four backends (Lisp source, `uiop-pathname.lisp`).
