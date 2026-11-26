# asdf:component-version

`(asdf:component-version component)`

Reader: the `:version` string the system's `defsystem` declared, or `nil` when
it declared none. A `.asd` file's `(:read-file-form "version.sexp" :at 0)` and
`(:read-file-line "version.txt" :at 0)` indirections read that file, beside the
`.asd`, as ASDF does. What does not parse as dotted integers answers `nil`, as does
any other spelling, an indirection the file cannot satisfy, and an indirection in a
`defsystem` outside a `.asd`. A component file has no version of its own.

```lisp
(asdf:defsystem :demo-cv :version "0.9.15" :components ((:file "main")))
(asdf:component-version (asdf:find-system :demo-cv)) ; => "0.9.15"
```

## Backend support

Works on all four backends, on any component object
([`asdf:find-system`](asdf-find-system.md)'s systems and their
[`asdf:component-children`](asdf-component-children.md)).
