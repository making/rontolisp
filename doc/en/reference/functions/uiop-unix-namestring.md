# uiop:unix-namestring

`(uiop:unix-namestring pathname)`

The Unix-style namestring of a pathname. A rontolisp namestring already is the
Unix spelling, so this is [`namestring`](namestring.md) with UIOP's tolerance:
`nil` and strings pass through unchanged.

```lisp
(uiop:unix-namestring #P"/a/b.c")   ; => "/a/b.c"
```

## Backend support

Works on all four backends (Lisp source, `uiop-pathname.lisp`).
