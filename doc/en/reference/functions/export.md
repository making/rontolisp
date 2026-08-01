# export

`(export symbols &optional package)`

Makes `symbols` (a symbol or a list of them) **external** in `package` (the current package by default), so they are visible unqualified through a [`use-package`](use-package.md) and spell with one colon rather than two. Returns `t`. It is the runtime form of the [`defpackage`](../special-forms/defpackage.md) `:export` clause; [`unexport`](unexport.md) is the inverse.

Packages are resolved at read/compile time here (see [Packages](../packages.md)), so a literal top-level call is consumed at compile time like [`in-package`](../special-forms/in-package.md) and takes effect for the forms that follow it — which is what makes it work on every backend. A runtime-computed call (a symbol list built at run time) works on the interpreter only.

**Export before you define.** A symbol is identified by its canonical spelling here, and exporting flips that spelling from `pkg::name` to `pkg:name`; a `defun` made *before* the export keeps the internal spelling and a later `pkg:name` call site will not find it. Put the `export` at the top of the file, or use the `defpackage` `:export` clause.

```lisp
(defpackage #:greeter2 (:use #:cl))
(in-package #:greeter2)
(export '(hello))
(defun hello () "hi")
(in-package #:cl-user)
(greeter2:hello) ; => "hi"
```
