# use-package

`(use-package packages &optional package)`

Adds `packages` (a package designator or a list of them) to the use list of `package` (the current package by default), so the **external** symbols of the used packages are visible unqualified afterwards. Returns `t`. Using a package twice is a no-op; using a package in itself is an error, as is an unknown package (`No such package: NOSUCH`). It is the runtime form of the [`defpackage`](../special-forms/defpackage.md) `:use` clause.

Packages are resolved at read/compile time here (see [Packages](../packages.md)), so a literal top-level call is consumed at compile time like `in-package` and takes effect for the forms that follow it — which is what makes it work on every backend. A runtime-computed call (a designator built at run time) works on the interpreter only. Internal symbols are never inherited: only what the used package `:export`s becomes visible.

```lisp
(defpackage #:greeter (:use #:cl) (:export #:hello))
(in-package #:greeter)
(defun hello () "hi")
(in-package #:cl-user)
(use-package '#:greeter)
(hello) ; => "hi"
```
