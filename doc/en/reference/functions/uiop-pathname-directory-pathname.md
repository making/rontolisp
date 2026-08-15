# uiop:pathname-directory-pathname

`(uiop:pathname-directory-pathname pathname)`

The pathname's directory as a pathname -- name and type dropped, everything up
to and including the last `/` kept. What
[`uiop:subpathname`](uiop-subpathname.md) merges under.

```lisp
(uiop:pathname-directory-pathname #P"/a/b/c.txt")   ; => #P"/a/b/"
```

## Backend support

Works on all four backends (Lisp source, `uiop-pathname.lisp`).
