# uiop:file-pathname-p

`(uiop:file-pathname-p pathname)`

The parsed PATHNAME when a name or type component is present -- the namestring
names a FILE rather than a directory -- and `nil` otherwise. Does **not** check
that the file exists (that is `uiop:file-exists-p`).

```lisp
(uiop:file-pathname-p "/a/b")   ; => #P"/a/b"
```

```lisp
(uiop:file-pathname-p "/a/b/")   ; => NIL
```

## Backend support

Works on all four backends (Lisp source, `uiop-pathname.lisp`).
