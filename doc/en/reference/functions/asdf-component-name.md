# asdf:component-name

`(asdf:component-name component)`

Reader: the component's name as its definition gives it, a symbol downcased.
A component's `:pathname` does not change it, and neither does the module around
it: `(:module "m" :components ((:file "two")))` holds a child named `"two"`.

```lisp
(asdf:defsystem :demo-cn :components ((:file "main")))
(asdf:component-name (asdf:find-system :demo-cn)) ; => "demo-cn"
```

## Backend support

Works on all four backends, on any component object
([`asdf:find-system`](asdf-find-system.md)'s systems and their
[`asdf:component-children`](asdf-component-children.md)).
