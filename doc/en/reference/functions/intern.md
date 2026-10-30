# intern

`(intern string [package])`

Returns the symbol named by `string` (no case folding) in `package` -- the **current package** by default (Common Lisp's `*package*` semantics): an accessible symbol keeps its home spelling, and an unknown name becomes a symbol of the package and is **recorded in its member table**, so a later [`find-symbol`](find-symbol.md) answers it and [`do-symbols`](../macros/do-symbols.md) enumerates it. rontolisp symbols compare by name, so the result is `eq` to any symbol with the same spelling, including quoted literals -- which is what lets a macro-time `(intern (concatenate ...))` under `(in-package p)` name the same function as a literal `defun` in that file. A string that already carries a package qualifier (`"LIB:WIDGET"`) names that symbol rather than a fresh symbol of the whole string, so a canonical spelling produced at run time — the type name [`type-of`](type-of.md) reads off a class, say — round-trips. `(intern name :keyword)` builds a keyword, and the package argument accepts any package designator — a keyword, a string, or a package value held in a variable; a package that does not exist signals a `package-error` whose [`package-error-package`](package-error-package.md) is the designator, and creates nothing. Like `find-symbol`, a second value reports the accessibility status the name had **before** the intern (`nil` for a fresh name, as in Common Lisp); the function object `#'intern` takes the same optional package and answers the same two values. The names `"T"` and `"NIL"` answer `t` and `nil` themselves on every backend -- those two are singletons, not symbols of that spelling.

Deviations from Common Lisp: on the compiled backends a package-qualified `intern` into a read/compile-time package builds the symbol's spelling. In a `defpackage` of the compiled program a name it does not export is the internal `PKG::NAME`, `eq` to its double-colon literal, and an exported one `PKG:NAME`; in a built-in package (`uiop`, `bordeaux-threads`, ...) named by a computed designator the build is `PKG:NAME`. A fresh name's second value is that build's status (`:internal`, or `:external` in a built-in package) instead of `nil`, and the function object `#'intern` called with one argument interns into `cl-user` whatever the current package. A name the package already reaches answers that symbol instead, as [`find-symbol`](find-symbol.md) does: an import, a re-export, a symbol inherited through `:use` (a standard name through `cl` included), or a member its `defpackage` records (`:shadow`, `:intern`). A package the program creates with [`make-package`](make-package.md) keeps its member table on every backend, and an intern into it records the member exactly as the interpreter does.

```lisp
(intern "hello") ; => |hello|
```

```lisp
(defpackage :in-std (:use :cl))
(funcall (intern (string-upcase "list") :in-std) 1 2) ; => (1 2)
```

```lisp
(eq (intern "foo") 'foo) ; => NIL
```

```lisp
(list (eq (intern "T") t) (eq (intern "NIL") nil)) ; => (T T)
```

```lisp
(defvar *level* 7)
(symbol-value (intern "*LEVEL*")) ; => 7
```

```lisp
(defpackage :evt (:use :cl) (:export :fire))
(in-package :evt)
(defun fire (x) (list :fired x))
(in-package :cl-user)
(funcall (intern "FIRE" :evt) 7) ; => (:FIRED 7)
```

```lisp
(defpackage :in-int (:use :cl) (:export :run))
(list (eq (intern "HELPER" :in-int) 'in-int::helper)
      (eq (intern "RUN" :in-int) 'in-int:run)) ; => (T T)
```

```lisp
(make-package :in-demo :use nil)
(find-symbol "FRESH" :in-demo) ; => NIL
(multiple-value-list (intern "FRESH" :in-demo)) ; => (IN-DEMO::FRESH NIL)
(multiple-value-list (intern "FRESH" :in-demo)) ; => (IN-DEMO::FRESH :INTERNAL)
(multiple-value-list (find-symbol "FRESH" :in-demo)) ; => (IN-DEMO::FRESH :INTERNAL)
```

```lisp
(handler-case (intern "X" "NO-SUCH-PKG")
  (package-error (e) (package-error-package e))) ; => :NO-SUCH-PKG
```
