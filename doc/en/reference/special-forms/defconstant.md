# defconstant

`(defconstant name value)`

Defines a global named `name` bound to `value`, evaluating `value` and returning the name symbol. It behaves like `defparameter` -- rontolisp does not enforce constancy, so the binding can still be changed afterward. It is intended to document values meant to stay fixed.

```lisp
(defconstant +pi+ 3.14159) ; => +PI+
```

```lisp
(defconstant +pi+ 3.14159)
+pi+ ; => 3.14159
```
