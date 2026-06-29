# Packages

rontolisp has a small namespace (package) system with three built-in packages:

- **`cl`** — the standard package. All built-in functions, macros, special forms and the `*package*` variable belong here.
- **`cl-user`** — the default working package. It *uses* `cl`, so standard symbols are available unqualified. The current package when a program starts. User definitions go here.
- **`rontolisp`** — a package for implementation-specific symbols. It does **not** use `cl`. It owns the `version`, `list-functions`, `list-macros` and `list-special-forms` functions.

A symbol can be referenced with a package qualifier, `package:symbol` (e.g. `cl:car`, `rontolisp:version`). `*package*` evaluates to the name of the current package, and `(in-package name)` switches it (the name is a keyword, a symbol, or a string: `:rontolisp`, `rontolisp`, `"rontolisp"`).

```lisp
(print *package*)              ; => cl-user
(print (rontolisp:version))    ; => (:version "0.1.0-SNAPSHOT" :build-timestamp "..." :git-commit "..." :git-branch "...")
```

`rontolisp:version` returns the same information as `rontolisp --version`, as a property list.

Because the `rontolisp` package does not use `cl`, standard symbols must be qualified with `cl:` inside it, while `version` (which it owns) is available unqualified:

```console
(in-package rontolisp)
(cl:print (version))           ; the rontolisp package owns version
(cl:print (cl:car '(1 2)))     ; standard symbols need the cl: prefix here
;; (car '(1 2)) would be an error: Undefined symbol: car (use cl:car)
```

The default package `cl-user` is empty and uses `cl`, so ordinary programs do not need any qualifiers.

## Package introspection

`rontolisp:list-functions`, `rontolisp:list-macros` and `rontolisp:list-special-forms` return the symbols of a package by category, sorted alphabetically. The optional argument is a package designator — a keyword, a bare symbol, a quoted symbol or a string (`:cl`, `cl`, `'cl`, `"cl"`) — and defaults to `:cl`. An unknown package is an error (`No such package: foo`).

```lisp
(print (rontolisp:list-macros))
; => (and case ccase cond decf do do* dolist dotimes ecase error etypecase format incf let* or pop prog1 prog2 psetq push remf setf time typecase unless when with-open-file)
(print (rontolisp:list-special-forms))
; => (defconstant defparameter defun defvar function if in-package lambda let progn quote return setq while)
(print (length (rontolisp:list-functions)))
; => 186
(defun square (x) (* x x))
(print (rontolisp:list-functions :cl-user))
; => (square)
(print (rontolisp:list-functions :rontolisp))
; => (fetch list-functions list-macros list-special-forms version)
```

The classification follows the function namespace: a name is listed as a function exactly when it is usable as a function value via `#'name` (so `first`, `length`, `1+`, ... are functions even though they compile via inline expansion), and `list-macros`/`list-special-forms` list the operators that have no function value. Notes:

- `list-functions` of `cl-user` lists the user-defined functions (`defun`s); names that are package-qualified, `%`-prefixed internals or shadow a `cl` symbol are excluded. In compiled output it is a **compile-time snapshot** of the program's `defun`s — functions defined at runtime through `load`/`eval` (even with `--dynamic`) are not included, and functions defined while `(in-package :rontolisp)` is in effect are not listed for any package.
- Car/cdr compositions (`cadr`, `caddr`, ...) are recognized by pattern, not enumerated, so they do not appear in `list-functions`.
- The package designator must be a literal; a computed designator is rejected at read/compile time (the interpreter additionally accepts a computed designator through `funcall`).
- Like `version`, these functions are not supported inside the compiled runtime `eval`/`load`.

Packages are resolved at read/compile time (in source order), so `in-package` is a top-level directive and `*package*` reflects the current package rather than being a mutable runtime variable. In compiled output a runtime-loaded file's package directives are not processed; the `rontolisp` package's functions (`version`, `list-functions`, ...) are not available as first-class values (they cannot be passed to `mapcar`/`funcall`); and a `cl` symbol name must not be shadowed as a local variable inside a package that does not use `cl`.

## rontolisp Package Extensions

The symbols the `rontolisp` package owns are **implementation-specific and not
part of Common Lisp**. They must be referenced with the `rontolisp:` qualifier
(or used unqualified after `(in-package rontolisp)`). Besides the introspection
helpers above (`version`, `list-functions`, `list-macros`, `list-special-forms`),
the package provides outgoing HTTP via `rontolisp:fetch`. All of these have their
own pages in the [Built-in Functions](builtin-functions.md#rontolisp-package-functions)
reference, including the full
[`rontolisp:fetch`](functions/rontolisp-fetch.md) documentation.
