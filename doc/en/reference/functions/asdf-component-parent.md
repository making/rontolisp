# asdf:component-parent

`(asdf:component-parent component)`

Reader: the component's parent component — the system for a source file, `nil`
for a system itself.

```lisp
(asdf:defsystem :demo-par :components ((:file "main")))
(asdf:component-parent (asdf:find-system :demo-par)) ; => NIL
```

## Backend support

Works on all four backends.
[`asdf:component-system`](asdf-component-system.md) walks it up to the system.
