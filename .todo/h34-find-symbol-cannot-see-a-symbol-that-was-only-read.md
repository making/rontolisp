# h34. `find-symbol` cannot see a symbol that was only READ (plump)

Difficulty: High

Found 2026-10-30 probing `plump`. Its matcher DSL (`lexer.lisp:161`) dispatches on

```lisp
(case (find-symbol (string (car form)) "PLUMP-LEXER")
  (in 'matcher-range) (next 'matcher-next) ...)
```

`PLUMP-LEXER::IN` exists only because the reader interned it while reading that `case`.
Here it is invisible:

```lisp
(defpackage :pa (:use :cl)) (in-package :pa)
(defmacro mm (form) (case (find-symbol (string (car form)) "PA") (in ''matched) (t ''nope)))
(in-package :cl-user)
(pa::mm (in 1 2))                         ; SBCL: MATCHED   here: NOPE
(find-symbol "IN" "PA")                   ; SBCL: PA::IN, :INTERNAL   here: NIL, NIL
```

so `(in #\a #\z)` stays a call: `The function PLUMP-PARSER::IN is undefined`.
`.kb/symbol-runtime-api.md` records this as deliberate ("a symbol merely READ is still
invisible", interpreter) and says to re-evaluate "only when a consumer needs symbol
OBJECTS". plump is that consumer.

Scope: the reader should record a member of the package it interns into, at least on
the interpreter and for the macro-expansion-time `find-symbol` of the compile paths. The
compile paths' 2-arg lowering already builds a symbol for any name (`.todo/254`, the
opposite error). Weigh both together with `.todo/156` Phase 5 (the intern table).
Prerequisite for plump: h32 (`compiler-macro-function`).
