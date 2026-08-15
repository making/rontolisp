# uiop:subpathp

`(uiop:subpathp maybe-subpath base-pathname)`

When `maybe-subpath` sits under `base-pathname`, returns the relative pathname
that merges back onto the base to give `maybe-subpath`; `nil` otherwise. Both
arguments must be pathname OBJECTS, both absolute, and the base in directory
form.

```lisp
(uiop:subpathp #P"/tmp/foo/bar.txt" #P"/tmp/")   ; => #P"foo/bar.txt"
```

```lisp
(uiop:subpathp #P"/other/x" #P"/tmp/")   ; => NIL
```

## Backend support

Works on all four backends (Lisp source, `uiop-pathname.lisp`).
