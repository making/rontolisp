# uiop:relative-pathname-p

`(uiop:relative-pathname-p pathspec)`

The parsed PATHNAME when `pathspec` is relative -- it does not start with `/`
(the empty pathname included) -- and `nil` otherwise, the mirror of
[`uiop:absolute-pathname-p`](uiop-absolute-pathname-p.md).

```lisp
(uiop:relative-pathname-p "a/b")   ; => #P"a/b"
```

```lisp
(uiop:relative-pathname-p "/a/b")   ; => NIL
```

## Backend support

Works on all four backends (Lisp source, `uiop-pathname.lisp`).
