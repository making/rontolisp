# uiop:absolute-pathname-p

`(uiop:absolute-pathname-p pathspec)`

The parsed PATHNAME when `pathspec` is absolute (a generalized boolean, as
upstream), `nil` otherwise. A rontolisp namestring is the host spelling, so
"absolute" is the leading `/` -- there is no device or host component to weigh.

```lisp
(uiop:absolute-pathname-p "/tmp/x")   ; => #P"/tmp/x"
```

```lisp
(uiop:absolute-pathname-p "tmp/x")   ; => NIL
```

## Backend support

Works on all four backends (Lisp source, `uiop-pathname.lisp`).
