# intern

`(intern string)`

Returns the symbol named by `string` (no case folding). rontolisp symbols compare by name — there is no separate intern table — so the result is `eq` to any symbol with the same name, including quoted literals. On the interpreter the name is interned into the **current package** (Common Lisp's `*package*` semantics): an accessible symbol keeps its home spelling, and an unknown name becomes a symbol of the package selected by `in-package` — which is what lets a macro-time `(intern (concatenate ...))` name the same function as a literal `defun` in that file. A string that already carries a package qualifier (`"LIB:WIDGET"`) names that symbol rather than a fresh symbol of the whole string, so a canonical spelling produced at run time — the type name [`type-of`](type-of.md) reads off a class, say — round-trips. `(intern name :keyword)` builds a keyword, and `(intern name package)` accepts any package designator — a keyword, a string, or a package value held in a variable; a package that does not exist signals an error. Like `find-symbol`, a second value reports the accessibility status of the name (`nil` for a name the package does not already provide). Deviation from Common Lisp: on the compiled backends a package-qualified `intern` always yields the single-colon external spelling, so an unexported symbol interned this way is not `eq` to its double-colon literal (calling it as a function still works).

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
