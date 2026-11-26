# enough-namestring

`(enough-namestring pathname &optional defaults)`

The shortest namestring that still names the same file once merged against
`defaults` (which itself defaults to `*default-pathname-defaults*`, initially
`#P""`). The value is a STRING, not a pathname.

It is the inverse of [`merge-pathnames`](merge-pathnames.md): the merge prefixes
a relative namestring with the defaults' DIRECTORY, so the shortest namestring
is the path with that directory prefix removed. When the path does not start
with the prefix nothing can be dropped and the whole namestring is the answer.

```lisp
(list (enough-namestring "/a/b/c.lisp" "/a/")
      (enough-namestring "/a/b/c.lisp" "/x/")
      (namestring (merge-pathnames (enough-namestring "/a/b/c.lisp" "/a/") "/a/")))
; => ("b/c.lisp" "/a/b/c.lisp" "/a/b/c.lisp")
```

`*default-pathname-defaults*` is a genuine dynamic variable on every backend, so
binding it around a block of path work works:

```lisp
(let ((*default-pathname-defaults* #P"/a/b/"))
  (enough-namestring "/a/b/c.lisp"))   ; => "c.lisp"
```

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
