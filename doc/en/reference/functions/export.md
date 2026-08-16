# export

`(export symbols &optional package)`

Makes `symbols` (a symbol or a list of them) **external** in `package` (the current package by default), so they are visible unqualified through a [`use-package`](use-package.md) and spell with one colon rather than two. Returns `t`. It is the runtime form of the [`defpackage`](../special-forms/defpackage.md) `:export` clause; [`unexport`](unexport.md) is the inverse.

Packages are resolved at read/compile time here (see [Packages](../packages.md)), so a literal top-level call is consumed at compile time like `in-package` and takes effect for the forms that follow it — which is what makes it work on every backend. A runtime-computed call (a symbol list built at run time) works on the interpreter only.

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

A reference written *before* the `export` is still an error, as in Common Lisp — the symbol is not external yet at that point.

One deviation: a symbol exported *after* it was first named prints with the double colon (`greeter3::hi`), because the qualifier is part of the stored symbol here rather than recomputed at print time. Both spellings name the same symbol.
