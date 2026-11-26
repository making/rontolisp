# asdf:map-systems

`(asdf:map-systems function)`

Calls `function` with each registered system's metaobject, in registration order.
[`asdf:already-loaded-systems`](asdf-registered-systems.md) lists the names of those
loaded, `asdf:component-loaded-p` asks about one, and `asdf:registered-system` answers
one without searching. Returns nil.

```lisp
(asdf:defsystem :mapped :components ())
(let ((seen nil))
  (asdf:map-systems (lambda (system)
                      (when (equal (asdf:component-name system) "mapped")
                        (setq seen t))))
  seen) ; => T
```

## Backend support

Works on all four backends. A compiled program's systems are the ones in its registry
([`asdf:find-system`](asdf-find-system.md)).
