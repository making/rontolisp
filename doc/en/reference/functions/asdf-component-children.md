# asdf:component-children

`(asdf:component-children parent)`

Reader: a system's or a module's children, in the order the definition writes
them, which is not the load order when a `:depends-on` says otherwise. A child
is an instance of its class: `asdf:module` for a `:module`, `asdf:cl-source-file`
for a `:file`, `asdf:static-file` for a `:static-file`. A package-inferred
sub-system has one child, named `"lisp"`, as in real ASDF. Each child's
[`asdf:component-pathname`](asdf-component-pathname.md) is its resolved path and
its [`asdf:component-parent`](asdf-component-parent.md) is the system or module
that holds it. A file has no children, so calling this on one signals an error.

```lisp
(asdf:defsystem :demo-ch
  :components ((:file "one") (:module "m" :components ((:file "two")))))
(mapcar (lambda (c) (asdf:component-name c))
        (asdf:component-children (asdf:find-system :demo-ch))) ; => ("one" "m")
```

## Backend support

Works on all four backends.
