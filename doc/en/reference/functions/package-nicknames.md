# package-nicknames

`(package-nicknames package-designator)`

The nickname strings of the designated package, in sorted order -- empty when it
has none. An unknown designator signals a catchable `package-error`.

```lisp
(make-package :doc-pn :nicknames '(:dpn :dpn2))
(package-nicknames :doc-pn) ; => ("DPN" "DPN2")
(delete-package :doc-pn) ; => T
```
