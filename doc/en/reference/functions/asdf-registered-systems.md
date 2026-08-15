# asdf:registered-systems

`(asdf:registered-systems)`

Returns the downcased names of every registered system, in registration order:
systems declared by an [`asdf:defsystem`](asdf-defsystem.md) or a parsed
`.asd`, package-inferred sub-systems that have been derived, and built-in shim
systems that have been loaded.

```lisp
(asdf:defsystem :demo-a :components ((:file "main")))
(asdf:defsystem :demo-b :components ((:file "main")))
(asdf:registered-systems) ; => ("demo-a" "demo-b")
```

## Backend support

Works on all four backends. The interpreter answers its live registry; a
compiled program answers the registry baked at compile time.
