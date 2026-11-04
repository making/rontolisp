# h96. A symbol read with a leading `&` is not homed in the current package

Difficulty: Medium

Measured 2026-11-04, interpreter and JVM alike. `PackageResolver` special-cases the prefix (lines
2367 and 2789 hold the `startsWith("&")` tests); the plain name `%a` homes correctly.

```lisp
(defpackage :mr (:use :cl))
(in-package :mr)
(print (symbol-package '&a))        ; SBCL #<PACKAGE "MR">  rontolisp :CL-USER
(print (eq '&a (intern "&A")))      ; SBCL T                rontolisp NIL
```

So the name the reader produces and the name `intern` produces are two symbols. let-plus shows the
consequence: `define-structure-let+` builds `&PT` with `symbolicate` and specializes on it, while
user code reads `&pt`, so the dispatch misses:

```
WARNING: &PT looks like a LET+ keyword, but it has no expansion method defined.
Unsupported lambda-list keyword: &PT
```

It works in `cl-user`. Real consumer: cl-colors (`&rgb`, `&hsv`). Interpreter: loads, then
`rgb-combination` fails when called. JVM and WASM: a compile error, and through
`prove -> cl-ansi-text -> cl-colors` neither prove nor caveman2 compiles.

SBCL's answer for `rgb-combination` of black and white at 0.5: `#S(RGB :RED 0.5 :GREEN 0.5 :BLUE 0.5)`.

Check the lambda-list keywords stay CL's: `(symbol-package '&optional)` is `COMMON-LISP` and the
exact list of `lambda-list-keywords` should keep resolving there; any other `&name` is an ordinary
symbol. Pin in `ci-spec.yaml` with the two lines above.
