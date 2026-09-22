# shadowing-import

`(shadowing-import symbols &optional package)`

Imports `symbols` (a symbol or a list of them) into `package` (the current package by default) the way [`import`](import.md) does, but **displacing** a present symbol of the same name instead of signalling a conflict: the displaced symbol is uninterned from the package (an own symbol loses its home, as after [`unintern`](unintern.md)), and each imported name joins the package's shadowing symbols, so it stays the accessible one whatever the use list brings in. Returns `t`. It is the runtime form of the [`defpackage`](../special-forms/defpackage.md) `:shadowing-import-from` clause.

The record lives in the package's member table, so this works on every backend (the interpreter's live registry; the compiled backends' table of the packages the program creates with [`make-package`](make-package.md)). A read/compile-time package is frozen there and answers `t` without a change.

```lisp
(make-package :si-src :use nil)
(make-package :si-dst :use nil)
(intern "X" :si-dst)
(shadowing-import (intern "X" :si-src) :si-dst) ; => T
(multiple-value-list (find-symbol "X" :si-dst)) ; => (SI-SRC::X :INTERNAL)
(package-shadowing-symbols :si-dst) ; => (SI-SRC::X)
```
