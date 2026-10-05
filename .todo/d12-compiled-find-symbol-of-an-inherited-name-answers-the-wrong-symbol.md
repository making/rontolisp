# d12. Compiled `find-symbol` of an inherited name answers the user's symbol as `:external`

Difficulty: Medium

On the JVM and both WASM backends, `find-symbol` of a name a `defpackage` package inherits
through `:use` answers a symbol homed in the USING package with status `:external`; SBCL and
the interpreter answer the used package's symbol with `:inherited`. Literal and computed name
alike, keyword or string package designator alike:

```lisp
(defpackage :u1 (:use) (:export #:b1))
(defpackage :uall (:use :u1))
(print (multiple-value-list (find-symbol "B1" :uall)))   ; SBCL/interp: (U1:B1 :INHERITED)
(print (symbol-package (find-symbol "B1" :uall)))        ; compiled: (UALL:B1 :EXTERNAL), :UALL
```

`do-symbols` over the same package counts the right rows, so the baked universe carries the
inherited externals; the find path (`%baked-package-find` / the find-symbol lowerings,
`.kb/packages.md`) does not consult them. Distinct from `.todo/254` (an UNKNOWN name answering a
symbol). Pin on all four backends.
