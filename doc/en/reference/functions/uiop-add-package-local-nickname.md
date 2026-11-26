# uiop:add-package-local-nickname

`(uiop:add-package-local-nickname nickname package &optional (scope-package *package*))`

Gives `scope-package` a name of its own for `package`, and returns `scope-package`. With `scope-package` current, `nickname:symbol` reads as `package:symbol`, the package designator `nickname` names `package`, and a symbol of `package` prints as `nickname:symbol`. It is the idiom libraries recommend for shortening long package names (e.g. jzon's `(uiop:add-package-local-nickname '#:jzon '#:com.inuoe.jzon)`). The [`defpackage`](../special-forms/defpackage.md) `:local-nicknames` clause does the same at package-definition time.

Adding a nickname again for the same package does nothing. A different package for a nickname `scope-package` already has signals a `package-error`, and so do `cl`, `common-lisp`, `keyword` and the name of `scope-package`.

[`uiop:package-local-nicknames`](../uiop.md) lists a package's nicknames as `("NICKNAME" . package)` pairs in nickname order. `uiop:remove-package-local-nickname` takes one away, answering `t` when the package had it and `nil` otherwise.

A literal top-level call takes effect for the forms read after it, on every backend. A computed call takes effect when it runs, so on the compile paths, which read a whole program before running any of it, only what looks a package up as it runs sees the nickname (`find-package`, `intern`, `read`, the printer), not a `nickname:symbol` written in the source.

A package value is the keyword of its name, so a package named like one of the current package's nicknames reads as the nickname when it is passed to a function that takes a package designator.

```lisp
(defpackage #:com.example.deeply.nested (:use #:cl) (:export #:answer))
(in-package #:com.example.deeply.nested)
(defun answer () 42)
(in-package #:cl-user)
(uiop:add-package-local-nickname '#:nick '#:com.example.deeply.nested)
(nick:answer) ; => 42
```
