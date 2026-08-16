# asdf:component-version

`(asdf:component-version component)`

Reader: the `:version` string the system's `defsystem` declared, or `nil` when
it declared none. Only a plain string literal is recorded — a `.asd` is parsed
as **data**, so ASDF's `(:read-file-form "version.sexp")` indirection (and any
other computed spelling) is never evaluated and answers `nil`. A component file
has no version of its own.

```lisp
(asdf:defsystem :demo-cv :version "0.9.15" :components ((:file "main")))
(asdf:component-version (asdf:find-system :demo-cv)) ; => "0.9.15"
```

## Backend support

Works on all four backends, on any component object
([`asdf:find-system`](asdf-find-system.md)'s systems and their
[`asdf:component-children`](asdf-component-children.md)).
