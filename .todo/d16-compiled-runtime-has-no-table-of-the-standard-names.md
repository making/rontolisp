# d16. The compiled runtime has no table of the standard names

Difficulty: Medium

A compiled program cannot tell at run time whether a name is one of the 978 standard
symbols, so every answer that needs it falls back to the spelling. SBCL and the interpreter
agree; JVM, P1 and component answer:

```lisp
(defpackage :sp1 (:use :cl))
(print (symbol-package 'car))                                          ; :CL       / compiled :CL-USER
(print (multiple-value-list (find-symbol (string-upcase "car") :sp1)))      ; (CAR :INHERITED) / (SP1:CAR :EXTERNAL)
(print (multiple-value-list (find-symbol (string-upcase "car") :cl-user)))  ; (CAR :INHERITED) / (CAR :INTERNAL)
```

A literal name is right everywhere (folded at compile time); only a computed name or package
designator is wrong. The `%baked-access` rows (`.kb/packages.md`, "Compiled lookups answer
through the registry") leave `cl` symbols out on purpose: the names packed are 13.7 KB, which
every program with a computed lookup would carry. Decide whether a consumer needs this
before paying that; if one does, one table should serve `symbol-package`, the
`find-symbol` / `intern` lookups and their status, gated on a computed site that can reach a
package using `cl`. Pin on all four backends.
