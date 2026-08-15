# pathname-version

`(pathname-version pathname)`

The version component of a pathname -- always `nil` here, for the reason
[`pathname-host`](pathname-host.md) is: rontolisp has no file versions, so no
namestring can carry one.

```lisp
(pathname-version #P"d/a.txt")   ; => NIL
```

Deviation: SBCL answers `:newest` for a pathname it PARSED from a namestring and
`nil` for one [`make-pathname`](make-pathname.md) built. `nil` -- "the component
is not present" -- is the one answer that is true of every pathname here.

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
