# uiop:subdirectories

`(uiop:subdirectories pathspec)`

The subdirectories of a directory, each with its trailing `/` and a link to a
directory among them under its own name: the
[`uiop:directory-files`](uiop-directory-files.md) twin, over the same
`(directory "<pathspec>/*.*" :resolve-symlinks nil)`, and what
[`uiop:collect-sub*directories`](uiop-collect-sub-directories.md) recurses
through.

```lisp
(uiop:subdirectories "no-such-directory/")   ; => NIL
```

## Backend support

All four backends, over the same one primitive [`directory`](directory.md) uses.
