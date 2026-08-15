# uiop:directory-pathname-p

`(uiop:directory-pathname-p pathname)`

`t` when the pathname is in directory form -- non-wild, with no name and no
type, i.e. empty or ending in `/`. Does **not** check that the directory
exists (that is `uiop:directory-exists-p`).

```lisp
(uiop:directory-pathname-p "/a/b/")   ; => T
```

```lisp
(uiop:directory-pathname-p "/a/b")   ; => NIL
```

## Backend support

Works on all four backends (Lisp source, `uiop-pathname.lisp`).
