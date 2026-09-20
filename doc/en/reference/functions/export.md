# export

`(export symbols &optional package)`

Makes `symbols` (a symbol or a list of them) **external** in `package` (the current package by default), so they are visible unqualified through a [`use-package`](use-package.md) and spell with one colon rather than two. Returns `t`. It is the runtime form of the [`defpackage`](../special-forms/defpackage.md) `:export` clause; [`unexport`](unexport.md) is the inverse.

As in Common Lisp, a symbol has to be accessible in the package it is exported from: the package's own symbols always are, while a symbol homed in another package must have been imported or be inherited through the use list -- an inherited one becomes present (imported) as it is exported, so users of the package inherit the home's symbol. Two name conflicts signal too: a different symbol of that name already accessible in the package, and a package USING this one that already has its own symbol of that name present (not shadowing). Every such failure is a catchable `package-error`. `unexport` leaves the symbol present, just no longer external, and leaves an inherited symbol alone.

Packages are resolved at read/compile time here (see [Packages](../packages.md)), so a literal top-level call is consumed at compile time like `in-package` and takes effect for the forms that follow it — which is what makes it work on every backend. A runtime-computed call (a symbol list built at run time) works on the interpreter, and on every backend for a package the program created with [`make-package`](make-package.md), whose member table records the export.

Exporting changes only *accessibility*, so it may come before or after the definitions it publishes — the everyday shape of a Common Lisp file, which defines its functions and exports them at the end, works:

```lisp
(defpackage #:greeter2 (:use #:cl))
(in-package #:greeter2)
(export '(hello))
(defun hello () "hi")
(in-package #:cl-user)
(greeter2:hello) ; => "hi"
```

```lisp
(defpackage #:greeter3 (:use #:cl))
(defun greeter3::hi () "hi")
(export '(greeter3::hi) :greeter3)
(greeter3:hi) ; => "hi"
```

```lisp
(make-package :exp-demo :use nil)
(export (intern "PUB" :exp-demo) :exp-demo) ; => T
(multiple-value-list (find-symbol "PUB" :exp-demo)) ; => (EXP-DEMO::PUB :EXTERNAL)
(handler-case (export 'not-mine :exp-demo) (package-error (c) (package-error-package c))) ; => :EXP-DEMO
```

A reference written *before* the `export` is still an error, as in Common Lisp — the symbol is not external yet at that point.

One deviation: a symbol exported *after* it was first named prints with the double colon (`greeter3::hi`), because the qualifier is part of the stored symbol here rather than recomputed at print time. Both spellings name the same symbol.
