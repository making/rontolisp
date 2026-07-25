# find-package

`(find-package designator)`

Lite: rontolisp has no package objects, so the returned "package" is the upcased canonical package name as a keyword, and `nil` for an unknown package. A literal designator is folded at compile time, so it works on all four backends; a computed designator is interpreter-only.

```lisp
(list (find-package :cl) (find-package "nope")) ; => (:CL NIL)
```
