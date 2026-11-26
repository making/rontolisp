# rename-package

`(rename-package package-designator new-name &optional new-nicknames)`

Renames a package created by [`make-package`](make-package.md) and returns it
under its new name. The nicknames are *replaced* by `new-nicknames`, even when
that list is empty. Renaming a read/compile-time package, an unknown designator,
or onto a name (or nickname) that collides with a different package signals a
catchable `package-error`.

```lisp
(make-package :doc-rp :nicknames '(:drp))
(rename-package :drp :doc-rp2 :drp2) ; => :DOC-RP2
(package-nicknames :doc-rp2) ; => ("DRP2")
(delete-package :doc-rp2) ; => T
```
