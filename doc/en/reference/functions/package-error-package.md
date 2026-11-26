# package-error-package

`(package-error-package condition)`

The offending package designator out of a `package-error` condition -- the value
[`make-package`](make-package.md) and friends signal it with. `nil` when the
failure names no package (an empty name).

```lisp
(handler-case (make-package :cl)
  (package-error (c) (package-error-package c))) ; => :CL
```
