# i74. `t` and `nil` read in a package that does not use `cl` are `cl`'s

Difficulty: Medium

Found 2026-11-05 running a coalton program (`.todo/i58`): `(let t = (hashtable:new))` in a package using coalton and
not `cl` stops on `unknown pattern literal`. SBCL 2.2.9 reads a bare `t` or `nil` there as the package's own symbol,
as it reads `car` (`.kb/packages.md`, "A package that does not read a standard name as `cl`'s"); here
`LispReader.readSymbol` answers `LispTrue`/`LispNil` for the names before the resolver sees them.

```lisp
(defpackage :tnil-c (:use))
(in-package :tnil-c)
(cl:print (cl:list 't 'nil (cl:eq 't cl:t) (cl:symbol-package 't)))
;; SBCL: (T NIL COMMON-LISP:NIL COMMON-LISP:NIL #<COMMON-LISP:PACKAGE "TNIL-C">)
;; here: (COMMON-LISP:T COMMON-LISP:NIL COMMON-LISP:T COMMON-LISP:T :CL)
(cl:let ((t 1)) (cl:print t))   ; SBCL 1, here a type error
```

- A package exporting its own `nil` (coalton's `Nil` constructor) is the same case: a bare `Nil` reads as
  `cl:nil` and `coalton:Nil` as `COALTON:NIL`, two values for one symbol. coalton's library loads because it
  only ever spells it bare.
- Reads before the resolver (a `.asd`'s data parse, `FeaturePushes`) rely on `nil` being `LispNil`.
- Verified means coalton's load again (an hour, `.kb/asdf.md`) beside the four backends.
