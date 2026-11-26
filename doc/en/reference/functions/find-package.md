# find-package

`(find-package designator)`

Lite: rontolisp has no package objects, so the returned "package" is the upcased canonical package name as a keyword, and `nil` for an unknown package. `designator` is a keyword, a string, a symbol, or `nil` (which designates the package named `"NIL"`, so it answers `nil`); package names are case-sensitive, exactly as in Common Lisp.

With a package current that has `designator` as a [local nickname](uiop-add-package-local-nickname.md), the answer is the package the nickname names. The package current when the call runs decides, so a literal designator some package has as a local nickname is not folded.

Any other literal designator is folded at compile time. A computed one is answered from the live registry on the interpreter, and on the compiled backends from a table of the program's packages baked in at compile time — so a package created after compilation is invisible there.

```lisp
(list (find-package :cl) (find-package "nope")) ; => (:CL NIL)
```
