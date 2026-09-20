# intern

`(intern string [package])`

Returns the symbol named by `string` (no case folding) in `package` -- the **current package** by default (Common Lisp's `*package*` semantics): an accessible symbol keeps its home spelling, and an unknown name becomes a symbol of the package and is **recorded in its member table**, so a later [`find-symbol`](find-symbol.md) answers it and [`do-symbols`](../macros/do-symbols.md) enumerates it. rontolisp symbols compare by name, so the result is `eq` to any symbol with the same spelling, including quoted literals -- which is what lets a macro-time `(intern (concatenate ...))` under `(in-package p)` name the same function as a literal `defun` in that file. A string that already carries a package qualifier (`"LIB:WIDGET"`) names that symbol rather than a fresh symbol of the whole string, so a canonical spelling produced at run time — the type name [`type-of`](type-of.md) reads off a class, say — round-trips. `(intern name :keyword)` builds a keyword, and the package argument accepts any package designator — a keyword, a string, or a package value held in a variable; a package that does not exist signals an error. Like `find-symbol`, a second value reports the accessibility status the name had **before** the intern (`nil` for a fresh name, as in Common Lisp); the function object `#'intern` takes the same optional package and answers the same two values.

Deviation from Common Lisp: on the compiled backends a package-qualified `intern` into a read/compile-time package (a built-in, or a `defpackage` of the compiled program) yields the single-colon external spelling, so an unexported symbol interned this way is not `eq` to its double-colon literal (calling it as a function still works). A package the program creates with [`make-package`](make-package.md) keeps its member table on every backend, and an intern into it records the member exactly as the interpreter does.

```lisp
(intern "hello") ; => |hello|
```

```lisp
(eq (intern "foo") 'foo) ; => NIL
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
(make-package :in-demo :use nil)
(find-symbol "FRESH" :in-demo) ; => NIL
(multiple-value-list (intern "FRESH" :in-demo)) ; => (IN-DEMO::FRESH NIL)
(multiple-value-list (intern "FRESH" :in-demo)) ; => (IN-DEMO::FRESH :INTERNAL)
(multiple-value-list (find-symbol "FRESH" :in-demo)) ; => (IN-DEMO::FRESH :INTERNAL)
```
