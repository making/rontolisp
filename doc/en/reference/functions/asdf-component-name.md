# asdf:component-name

`(asdf:component-name component)`

Reader: the component's downcase-canonical name. For a system that is the
system name; for a source file it is the file's path relative to the system,
minus the `.lisp` extension.

```lisp
(asdf:defsystem :demo-cn :components ((:file "main")))
(asdf:component-name (asdf:find-system :demo-cn)) ; => "demo-cn"
```

## Backend support

Works on all four backends, on any component object
([`asdf:find-system`](asdf-find-system.md)'s systems and their
[`asdf:component-children`](asdf-component-children.md)).
