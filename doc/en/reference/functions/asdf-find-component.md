# asdf:find-component

`(asdf:find-component base path &key registered)`

Finds a component by name. With `base` nil, `path` is a system designator (or a list
starting with one), and a name nothing defines answers nil. With a system or a system
designator or a module as `base`, `path` is a child's name or a list of names leading to
it, one per level of the tree; `nil` answers `base` itself. An unknown name answers nil,
and a name below a file signals an error. With `:registered` true, a system designator
only names a system already registered, so nothing is searched for.

```lisp
(asdf:defsystem :found :components ((:file "main") (:module "m" :components ((:file "two")))))
(asdf:component-name (asdf:find-component :found "main")) ; => "main"
(asdf:component-name (asdf:find-component :found "m")) ; => "m"
(asdf:component-name (asdf:find-component :found '("m" "two"))) ; => "two"
(asdf:find-component :found "nope") ; => NIL
```

## Backend support

Works on all four backends.
