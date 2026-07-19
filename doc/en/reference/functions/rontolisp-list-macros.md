# rontolisp:list-macros

`(rontolisp:list-macros &optional package)`

Returns the macro symbols of a package, sorted alphabetically -- the operators
that have no function value. The optional package designator defaults to `:cl`
and may be a keyword, a bare symbol, a quoted symbol, or a string. An unknown
package is an error. See
[Package introspection](../packages.md#package-introspection) for details.

```lisp
(rontolisp:list-macros) ; => (and assert block case ccase cerror check-type complement complex cond decf declaim declare define-compiler-macro define-condition define-modify-macro define-setf-expander defsetf deftype destructuring-bind do do* documentation dolist dotimes ecase error etypecase eval-when flet format handler-case ignore-errors incf labels let* load-time-value locally loop macrolet make-condition make-instance make-sequence multiple-value-bind multiple-value-call multiple-value-list multiple-value-setq nth-value or pop print-unreadable-object proclaim prog prog* prog1 prog2 psetf psetq push pushnew remf restart-case return-from rotatef setf shiftf signal slot-boundp slot-makunbound slot-value the time typecase typep unless warn when with-input-from-string with-open-file with-output-to-string with-package-iterator with-slots write-char)
```
