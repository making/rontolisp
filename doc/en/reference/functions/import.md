# import

`(import symbols &optional package)`

Makes `symbols` (a symbol or a list of them) accessible **unqualified** in `package` (the current package by default): a later bare `name` resolves to the imported symbol rather than to a fresh symbol of the importing package. Returns `t`. It is the runtime form of the [`defpackage`](../special-forms/defpackage.md) `:import-from` clause. The argument keeps its package qualifier — that is what says where the symbol comes from — and an unqualified symbol is already the current package's own, so importing it does nothing. An unknown package signals (`No such package: NOSUCH`).

Packages are resolved at read/compile time here (see [Packages](../packages.md)), so a literal top-level call is consumed at compile time like `in-package` and takes effect for the forms that follow it — which is what makes it work on every backend. A runtime-computed call (a symbol built at run time) works on the interpreter only.

```lisp
(defpackage #:importer (:use #:cl) (:export #:shout))
(in-package #:importer)
(defun shout () "HI")
(in-package #:cl-user)
(import 'importer:shout)
(shout) ; => "HI"
```
