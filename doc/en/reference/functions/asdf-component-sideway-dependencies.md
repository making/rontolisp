# asdf:component-sideway-dependencies

`(asdf:component-sideway-dependencies system)`

Reader: the system's `:depends-on` names, in order. For a package-inferred
system these are the names derived from the component file's own `defpackage`,
sub-system names included — which is what rove's
`package-inferred-system-component-names` filters by the primary's prefix.

```lisp
(asdf:defsystem :demo-deps :depends-on ("demo-base") :components ((:file "main")))
(asdf:defsystem :demo-base :components ((:file "base")))
(asdf:component-sideway-dependencies (asdf:find-system :demo-deps)) ; => ("demo-base")
```

## Backend support

Works on all four backends.
