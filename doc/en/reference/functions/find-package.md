# find-package

`(find-package designator)`

Lite: rontolisp has no package objects, so the returned "package" is the upcased canonical package name as a keyword, and `nil` for an unknown package. `designator` is a keyword, a string, a symbol, or `nil` (which designates the package named `"NIL"`, so it answers `nil`); package names are case-sensitive, exactly as in Common Lisp.

A literal designator is folded at compile time. A computed one is answered from the live registry on the interpreter, and on the compiled backends from a table of the program's packages baked in at compile time — so a package created after compilation is invisible there.

```lisp
(list (find-package :cl) (find-package "nope")) ; => (:CL NIL)
```
