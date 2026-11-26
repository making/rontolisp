# asdf:component-system

`(asdf:component-system component)`

Walks [`asdf:component-parent`](asdf-component-parent.md) up to the system a
component belongs to; a system answers itself.

```lisp
(asdf:defsystem :demo-cs :components ((:file "main")))
(let ((sys (asdf:find-system :demo-cs)))
  (eq (asdf:component-system (car (asdf:component-children sys))) sys)) ; => T
```

## Backend support

Works on all four backends.
