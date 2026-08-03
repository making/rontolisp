# package-name

`(package-name package-designator)`

The name of the designated package as a string. The designator is resolved through [`find-package`](find-package.md) first, so a nickname answers the canonical name; an unknown designator signals an error. A "package" value in rontolisp is its canonical upcased name as a keyword, so the name string is that keyword's string.

```lisp
(package-name (find-package :cl-user)) ; => "CL-USER"
```
