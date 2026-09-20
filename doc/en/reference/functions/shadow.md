# shadow

`(shadow symbol-names &optional package)`

Makes each name (a string designator, or a list of them) a **shadowing symbol** of `package` (the current package by default): a symbol of that name already present in the package -- owned or imported -- is marked as is, any other name is interned as a fresh own symbol first. From then on [`find-symbol`](find-symbol.md) answers that symbol with status `:internal` even when a used package exports one of the same name, and [`package-shadowing-symbols`](package-shadowing-symbols.md) lists it. Returns `t`. It is the runtime form of the [`defpackage`](../special-forms/defpackage.md) `:shadow` clause.

The package keeps the record in its member table, so this works on every backend: the interpreter writes its live registry, the compiled backends the table of the packages the program creates with [`make-package`](make-package.md). A read/compile-time package -- a built-in or a `defpackage` product of a compiled program -- is frozen there and answers `t` without a change.

```lisp
(make-package :sh-base :use nil)
(export (intern "X" :sh-base) :sh-base)
(make-package :sh-user :use '(:sh-base))
(multiple-value-list (find-symbol "X" :sh-user)) ; => (SH-BASE::X :INHERITED)
(shadow "X" :sh-user) ; => T
(multiple-value-list (find-symbol "X" :sh-user)) ; => (SH-USER::X :INTERNAL)
(package-shadowing-symbols :sh-user) ; => (SH-USER::X)
```
