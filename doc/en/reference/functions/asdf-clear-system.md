# asdf:clear-system

`(asdf:clear-system system)`

Forgets a system: `asdf:registered-system` answers nil for it, and the next
[`asdf:find-system`](asdf-find-system.md) searches again and answers a new instance. A
later load loads it again. Clearing a name nothing registered is not an error. Returns
`t`.

```lisp
(asdf:defsystem :cleared :components ())
(asdf:clear-system :cleared)
(asdf:registered-system :cleared) ; => NIL
```

## Backend support

Works on all four backends. A compiled program keeps what it spliced, so after a clear
a `find-system` answers a new instance of the same record and a load loads nothing.
