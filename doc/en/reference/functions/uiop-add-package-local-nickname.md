# uiop:add-package-local-nickname

`(uiop:add-package-local-nickname nickname package &optional scope-package)`

Registers `nickname` as a shorthand for `package`, so `nickname:symbol` resolves like `package:symbol` afterwards -- the idiom libraries recommend for shortening long package names (e.g. jzon's `(uiop:add-package-local-nickname '#:jzon '#:com.inuoe.jzon)`). Returns the target package's name symbol. The [`defpackage`](../special-forms/defpackage.md) `:local-nicknames` clause performs the same registration at package-definition time.

Lite: the nickname is **global** -- rontolisp has no per-package nickname scoping, so the optional third argument (the package to scope the nickname to) is accepted and ignored, and the nickname follows the same collision rules as `defpackage` `:nicknames`. On the JVM/WASM compile path the call must be a literal top-level form (literal designator arguments); it is consumed at compile time like a `defpackage`. A runtime-computed call works on the interpreter only.

```lisp
(defpackage #:com.example.deeply.nested (:use #:cl) (:export #:answer))
(in-package #:com.example.deeply.nested)
(defun answer () 42)
(in-package #:cl-user)
(uiop:add-package-local-nickname '#:nick '#:com.example.deeply.nested)
(nick:answer) ; => 42
```
