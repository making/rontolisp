# package-shadowing-symbols

`(package-shadowing-symbols package)`

The shadowing symbols of `package`, in name order: the names its [`defpackage`](../special-forms/defpackage.md) `:shadow` and `:shadowing-import-from` clauses declared, and the ones [`shadow`](shadow.md) and [`shadowing-import`](shadowing-import.md) added at run time, each spelled as the symbol the package makes accessible under it. [`unintern`](unintern.md) takes a symbol out of the list. The designator is validated, so an unknown package signals like [`package-name`](package-name.md).

```lisp
(package-shadowing-symbols :cl-user) ; => NIL
```

```lisp
(defpackage :pss-base (:use) (:export :a))
(defpackage :pss-demo (:use :pss-base) (:shadow :b) (:shadowing-import-from :pss-base :a))
(package-shadowing-symbols :pss-demo) ; => (PSS-BASE:A PSS-DEMO::B)
```
