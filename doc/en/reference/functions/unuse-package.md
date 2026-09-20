# unuse-package

`(unuse-package packages &optional package)`

The inverse of [`use-package`](use-package.md): removes `packages` (a package designator or a list of them) from the use list of `package` (the current package by default), so their external symbols stop being visible unqualified. Returns `t`. Unusing a package that is not used is a no-op; an unknown package is an error (`No such package: NOSUCH`).

Consumed at compile time exactly like `use-package`, so a literal top-level call takes effect for the forms that follow it and works on every backend. A runtime-computed call runs on the interpreter, where it rewrites the live use list; on the compiled backends the registry is frozen, so such a call evaluates its arguments and answers `t` without changing anything — the same rule [`export`](export.md) follows.

```lisp
(defpackage #:toolbox (:use #:cl) (:export #:widget))
(in-package #:toolbox)
(defun widget () 7)
(in-package #:cl-user)
(use-package '#:toolbox)
(widget) ; => 7
(unuse-package '#:toolbox)
(toolbox:widget) ; => 7
```
