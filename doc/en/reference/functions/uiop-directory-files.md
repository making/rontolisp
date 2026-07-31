# uiop:directory-files

`(uiop:directory-files pathspec)`

The non-directory entries of a directory: `(directory "<pathspec>/*.*")` with the
subdirectories dropped. `pathspec` names the directory itself (with or without a
trailing `/`) -- the wildcard is supplied here, so this is the "just list it"
spelling.

```lisp
(uiop:directory-files "no-such-directory/")   ; => NIL
```

Real UIOP takes an optional second argument, a wildcard pathname to filter by;
rontolisp has no wildcard pathname machinery, so only the one-argument shape is
offered -- filter the returned list in Lisp.

## Backend support

All four backends, over the same one primitive `directory` uses.
