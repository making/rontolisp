# uiop:parse-unix-namestring

`(uiop:parse-unix-namestring name &key type defaults dot-dot ensure-directory &allow-other-keys)`

Coerces `name` into a pathname using Unix syntax -- UIOP's portable pathname
reader. A pathname passes through, `nil` stays `nil`, a symbol is downcased and
read as a string. Empty and `"."` directory components are dropped; `".."` is
kept as one level up. `:type` a string makes the whole last component the NAME
with that type; `:ensure-directory` (or `:type :directory`) forces directory
form. Remaining keys go to [`uiop:ensure-pathname`](uiop-ensure-pathname.md)
(so `:want-relative t` rejects an absolute string).

```lisp
(uiop:parse-unix-namestring "a//b/./c.txt")   ; => #P"a/b/c.txt"
```

```lisp
(uiop:parse-unix-namestring "foo/bar" :type "lisp")   ; => #P"foo/bar.lisp"
```

## Backend support

Works on all four backends (Lisp source, `uiop-pathname.lisp`).
