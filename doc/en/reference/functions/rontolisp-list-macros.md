# rontolisp:list-macros

`(rontolisp:list-macros &optional package)`

Returns the macro symbols of a package, sorted alphabetically -- the operators
that have no function value. The optional package designator defaults to `:cl`
and may be a keyword, a bare symbol, a quoted symbol, or a string. An unknown
package is an error. See
[Package introspection](../packages.md#package-introspection) for details.

```lisp
(rontolisp:list-macros) ; => (and assert case ccase check-type cond decf declaim declare do do* dolist dotimes ecase error etypecase eval-when flet format incf labels let* loop multiple-value-bind multiple-value-call multiple-value-list nth-value or pop proclaim prog1 prog2 psetq push remf setf the time typecase unless when with-open-file)
```
