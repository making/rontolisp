# import

`(import symbols &optional package)`

Makes `symbols` (a symbol or a list of them) accessible **unqualified** in `package` (the current package by default): a later bare `name` resolves to the imported symbol rather than to a fresh symbol of the importing package, and [`find-symbol`](find-symbol.md) answers it with status `:internal`. Returns `t`. It is the runtime form of the [`defpackage`](../special-forms/defpackage.md) `:import-from` clause. Each symbol is imported from its home -- a package-qualified symbol from the package its qualifier names, a bare one from `cl-user` (or `cl`, for a standard name) -- and a symbol the package already owns is a no-op. A **different** symbol of the same name already present in the package (owned or imported, not merely inherited) is a name conflict and signals a catchable `package-error`; [`shadowing-import`](shadowing-import.md) is the form that displaces it instead. An unknown package signals (`No such package: NOSUCH`).

Packages are resolved at read/compile time here (see [Packages](../packages.md)), so a literal top-level call is consumed at compile time like `in-package` and takes effect for the forms that follow it — which is what makes it work on every backend. A runtime-computed call (a symbol built at run time) works on the interpreter, and on every backend for a package the program created with [`make-package`](make-package.md), whose member table records the import.

```lisp
(defpackage #:importer (:use #:cl) (:export #:shout))
(in-package #:importer)
(defun shout () "HI")
(in-package #:cl-user)
(import 'importer:shout)
(shout) ; => "HI"
```

```lisp
(make-package :imp-demo :use nil)
(import 'my-sym :imp-demo) ; => T
(multiple-value-list (find-symbol "MY-SYM" :imp-demo)) ; => (MY-SYM :INTERNAL)
(intern "OTHER" :imp-demo)
(handler-case (import 'other :imp-demo) (package-error (c) (package-error-package c))) ; => :IMP-DEMO
```
