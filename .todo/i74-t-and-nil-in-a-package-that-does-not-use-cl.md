# i74. `t` and `nil` read in a package that does not use `cl` are `cl`'s

Difficulty: High

Found 2026-11-05 running coalton programs (`.todo/i58`). SBCL 2.2.9 reads a bare `t` or `nil` in a package that
does not inherit `cl`'s as that package's own symbol (or the one it inherits), as it reads `car` (`.kb/packages.md`,
"A package that does not read a standard name as `cl`'s"); here `LispReader.readSymbol` answers `LispTrue`/`LispNil`
for the names before any package is consulted.

```lisp
(defpackage :tnil-c (:use))
(in-package :tnil-c)
(cl:print (cl:list 't 'nil (cl:eq 't cl:t) (cl:symbol-package 't)))
;; SBCL: (T NIL COMMON-LISP:NIL COMMON-LISP:NIL #<COMMON-LISP:PACKAGE "TNIL-C">)
;; here: (COMMON-LISP:T COMMON-LISP:NIL COMMON-LISP:T COMMON-LISP:T :CL)
(cl:let ((t 1)) (cl:print t))   ; SBCL 1, here a type error
```

- What it breaks: a coalton program read without coalton's readtable (its docs call the `in-readtable`
  optional). `Nil` is `coalton:Nil`, the list constructor, and reads as `cl:nil`: `unexpected nil or ()` from
  coalton's parser; `(let t = ...)` is `unknown pattern literal`. Under `(named-readtables:in-readtable
  coalton:coalton)` eclector finds the symbols itself and both work; the library always reads that way.
- The decision needs the package the token is read in. The interpreter knows it form by form; the compile paths
  resolve packages in a pass after the read, and passes before that pass test raw `nil`s as `LispNil`
  (`.asd` data, `FeaturePushes`, the top-level scans). `()` stays `cl:nil` everywhere.
- Verified means coalton's load again (an hour, `.kb/asdf.md`) beside the four backends.
