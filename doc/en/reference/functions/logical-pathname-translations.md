# logical-pathname-translations

`(logical-pathname-translations host)`, `(setf (logical-pathname-translations host) translations)`

The translation list recorded for a logical host. `setf` defines the host (the
name is case-insensitive) or replaces its list; reading a host never defined
signals a `type-error`, as SBCL does. Each translation is
`(from-wildcard to-wildcard)`: a logical from-wildcard such as `"**;*.*.*"` and
the physical to-wildcard it maps to, read by
[`translate-logical-pathname`](translate-logical-pathname.md).

```lisp
(setf (logical-pathname-translations "APP") '(("**;*.*.*" "/opt/app/**/*.*")))
(logical-pathname-translations "app")
; => (("**;*.*.*" "/opt/app/**/*.*"))
```

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
