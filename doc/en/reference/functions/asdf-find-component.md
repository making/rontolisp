# asdf:find-component

`(asdf:find-component base path)`

Finds a component by name. With `base` nil, `path` is a system designator (or a list
starting with one), and a name nothing defines answers nil. With a system or a system
designator as `base`, `path` is a child's name or a list of names leading to it; `nil`
answers `base` itself. An unknown name answers nil.

A system's children are flat here ([`asdf:component-children`](asdf-component-children.md)):
a path is matched against a child's relative path, whole or from a directory boundary, so
a `:module` itself is not a component this finds.

```lisp
(asdf:defsystem :found :components ((:file "main") (:module "m" :components ((:file "two")))))
(asdf:component-name (asdf:find-component :found "main")) ; => "main"
(asdf:component-name (asdf:find-component :found '("m" "two"))) ; => "m/two"
(asdf:find-component :found "nope") ; => NIL
```

## Backend support

Works on all four backends.
