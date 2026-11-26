# uiop:enough-pathname

`(uiop:enough-pathname maybe-subpath base-pathname)`

The [`uiop:subpathp`](uiop-subpathp.md) remainder when there is one, the
pathname itself otherwise -- the shortest spelling that still names the file
given the base. rove keys its source-location printing on it.

```lisp
(uiop:enough-pathname #P"/tmp/a/b.txt" #P"/tmp/")   ; => #P"a/b.txt"
```

```lisp
(uiop:enough-pathname #P"/x/a.txt" #P"/tmp/")   ; => #P"/x/a.txt"
```

`uiop:with-enough-pathname` / `uiop:call-with-enough-pathname` run a body with
this value, under `*default-pathname-defaults*` bound to the base
([uiop/pathname](../uiop/pathname.md#relative-to-a-base)).

## Backend support

Works on all four backends (Lisp source, `uiop-pathname.lisp`).
