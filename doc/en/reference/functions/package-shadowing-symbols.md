# package-shadowing-symbols

`(package-shadowing-symbols package)`

Always `nil`: rontolisp has no symbol shadowing. The [`defpackage`](../special-forms/defpackage.md) `:shadow` clause records names for *resolution* and mints no shadowing symbol, and the runtime `shadow` / `shadowing-import` do not exist. The designator is still validated, so an unknown package signals like [`package-name`](package-name.md).

```lisp
(package-shadowing-symbols :cl-user) ; => NIL
```
