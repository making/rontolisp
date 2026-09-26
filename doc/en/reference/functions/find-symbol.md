# find-symbol

`(find-symbol string [package])`

Like [`intern`](intern.md) but never creates: returns the symbol `package` -- the current package by default -- makes **accessible** under the name, nil otherwise. Accessible means a symbol **present** in the package (what [`intern`](intern.md), [`export`](export.md), [`import`](import.md), [`shadowing-import`](shadowing-import.md) and [`shadow`](shadow.md) recorded in its member table, or a definition made under it -- a `defun` is an interning), a symbol **inherited** from a package it uses (that package's exports), a standard name reached through `cl`, or a keyword. A symbol merely read in the source under the package is not recorded, and a name [`unintern`](unintern.md) took out is no longer the package's own. A designator that names no package (`nil` included) signals a `package-error` whose [`package-error-package`](package-error-package.md) is the designator, as in Common Lisp — a probe for an optional system guards on [`find-package`](find-package.md) first.

Deviations from Common Lisp: on the compiled backends (JVM/WASM) only a **literal** string can answer `nil` for a read/compile-time package — the check is folded at compile time against the compile-time view (cl symbols plus the program's own `defun`s), so runtime-defined variables and macros are not visible there (the interpreter checks the live image, including global variables and `defmacro` macros). A computed name is interned instead, so it always yields a symbol, and its status is read off the spelling that lowering builds (`:external` for a qualified one, `:internal` for a bare one) rather than from the image. A package the program creates with [`make-package`](make-package.md) is the exception: its member table is consulted on every backend, so `nil` before an intern and the symbol after hold there whatever the arguments. The function object `#'find-symbol` answers the same two values; on the compiled backends its arguments are computed ones.

With `common-lisp` (or `cl`) as the package, the answer is the **standard's** name list: CLHS 11.1.2.1 makes the package export all 978 standard names, so a name is found whether or not rontolisp implements an operator behind one. Being exported is not being defined — `fboundp`, `macro-function` and `special-operator-p` still answer `nil` for such a name, calling it still signals `undefined-function`, and a program may still `defun` it. A package that uses `cl` inherits those names the same way (`:inherited`), and `t` and `nil` come back as themselves.

A second value reports the ANSI accessibility status of the name in that package — `:external`, `:inherited`, `:internal`, or `nil` when the package does not provide it, so the two values are `nil` together:

```lisp
(multiple-value-list (find-symbol "CAR" 'common-lisp)) ; => (CAR :EXTERNAL)
```

```lisp
(multiple-value-list (find-symbol "CAR")) ; => (CAR :INHERITED)
```

```lisp
(find-symbol "car") ; => NIL
```

```lisp
(find-symbol "cond") ; => NIL
```

```lisp
(find-symbol "no-such-name") ; => NIL
```

```lisp
(defun greet (n) n)
(find-symbol "greet") ; => NIL
```

```lisp
(and (find-package :simple-date) (find-symbol "TIMESTAMP" :simple-date)) ; => NIL
```

```lisp
(handler-case (find-symbol "X" "NO-SUCH-PKG")
  (package-error (e) (package-error-package e))) ; => :NO-SUCH-PKG
```

```lisp
(multiple-value-list (find-symbol "FIND-METHOD" 'common-lisp)) ; => (FIND-METHOD :EXTERNAL)
```

```lisp
(fboundp 'find-method) ; => NIL
```

```lisp
(defpackage :fs-demo (:use :cl))
(multiple-value-list (find-symbol "CAR" :fs-demo)) ; => (CAR :INHERITED)
(find-symbol "NEVER-INTERNED" :fs-demo) ; => NIL
(eq (find-symbol "T" :fs-demo) t) ; => T
```
