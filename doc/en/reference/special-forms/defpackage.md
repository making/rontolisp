# defpackage

`(defpackage name (:use package...) (:export symbol...))`

Defines a new package named `name` and returns the name symbol. Like `in-package`, it is a literal, top-level directive consumed at read/compile time, so packages are defined in source order. The name and the clause arguments are keywords, bare symbols, or strings (`:mypkg`, `mypkg`, `"mypkg"`).

- `(:use package...)` makes the external (exported) symbols of the listed packages visible unqualified. The used packages must already exist. Without a `:use` clause **nothing** is visible unqualified — write `(:use :cl)` to use the standard symbols without a `cl:` prefix.
- `(:export symbol...)` declares the package's external symbols: they are reachable as `name:symbol` from other packages, and inherited by packages that use this one. Symbols interned later (for example `defun`s made under `(in-package name)` that are not in the `:export` clause) are internal and require the double colon, `name::symbol`.

Redefining an existing package is an error, and so is any other clause (`:nicknames`, `:shadow`, `:import-from`, `:documentation`, ...). See [Packages](../packages.md#user-defined-packages-defpackage) for the full rules.

```lisp
(defpackage :util (:use :cl) (:export :trim)) ; => util
```

```lisp
(defpackage :mypkg (:use :cl) (:export :greet))
(in-package :mypkg)
(defun greet (name) (concatenate 'string "hello, " name))
(in-package :cl-user)
(mypkg:greet "world") ; => "hello, world"
```
