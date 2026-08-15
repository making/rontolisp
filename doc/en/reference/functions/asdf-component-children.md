# asdf:component-children

`(asdf:component-children parent)`

Reader: a parent component's children in load order. A system's children are
its component files, **one `asdf:cl-source-file` per file** — a `:module`
contributes its path prefix to the file names rather than a nested instance —
and a package-inferred sub-system has exactly one child, real ASDF's shape.
Each child's [`asdf:component-pathname`](asdf-component-pathname.md) is the
resolved source path and its
[`asdf:component-parent`](asdf-component-parent.md) is the system.

```lisp
(asdf:defsystem :demo-ch
  :components ((:file "one") (:module "m" :components ((:file "two")))))
(mapcar (lambda (c) (asdf:component-name c))
        (asdf:component-children (asdf:find-system :demo-ch))) ; => ("one" "m/two")
```

## Backend support

Works on all four backends.
