# rontolisp:list-special-forms

`(rontolisp:list-special-forms &optional package)`

Returns the special-form symbols of a package, sorted alphabetically -- the
operators evaluated specially that have no function value. The optional package
designator defaults to `:cl` and may be a keyword, a bare symbol, a quoted
symbol, or a string. An unknown package is an error. See
[Package introspection](../packages.md#package-introspection) for details.

```lisp
(rontolisp:list-special-forms) ; => (defconstant defparameter defun defvar function if in-package lambda let progn quote return setq while)
```
