# uiop:ensure-pathname

`(uiop:ensure-pathname pathname &key on-error defaults type dot-dot empty-is-nil want-pathname want-relative want-absolute ensure-absolute ensure-subpath want-file want-directory ensure-directory want-non-wild want-wild wilden want-existing ensure-directories-exist truename &allow-other-keys)`

The constraint machine the rest of uiop routes through: coerces a designator (a
string goes through
[`uiop:parse-unix-namestring`](uiop-parse-unix-namestring.md)), then applies the
`:want-*` checks and `:ensure-*` transforms in upstream's order. A failed check
signals an error naming the pathname and the constraint, or calls a custom
`:on-error` function.

```lisp
(uiop:ensure-pathname "a/b" :ensure-directory t)   ; => #P"a/b/"
```

```lisp
(handler-case (uiop:ensure-pathname "/a/b" :want-relative t)
  (error () :err))   ; => :ERR
```

Lite next to upstream, deliberately: the report is `Invalid pathname ~S: ~A`,
`:want-logical` always fails (no logical pathname exists),
`:resolve-symlinks` / `:truenamize` are accepted and ignored, and `:truename`
answers what [`probe-file`](probe-file.md) answers.

## Backend support

Works on all four backends (Lisp source, `uiop-pathname.lisp`).
