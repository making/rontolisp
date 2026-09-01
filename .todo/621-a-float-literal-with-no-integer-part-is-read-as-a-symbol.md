# `.4` reads as a symbol, and `1.` is a read error

Difficulty: Low

The reader requires at least one digit before the decimal point, and rejects a
decimal point with no digits after it. Both spellings are ordinary CL number
syntax (CLHS 2.3.1), and both appear in real code.

```lisp
.4        ; rontolisp: the symbol |.4|  -> "The variable .4 is unbound"   SBCL: 0.4
-.5       ; rontolisp: the symbol |-.5|                                   SBCL: -0.5
+.25      ; rontolisp: the symbol |+.25|                                  SBCL: 0.25
.5e1      ; rontolisp: the symbol |.5E1|                                  SBCL: 5.0
1.        ; rontolisp: read error, "Unexpected ')'"                       SBCL: 1
100.      ; rontolisp: read error                                         SBCL: 100
```

`1e3`, `1.0e-3` and `1d2` are already right, so this is the two ends of the
grammar and not the float reader itself. A trailing decimal point is a DECIMAL
INTEGER (`1.` is the integer 1 whatever `*read-base*` is), not a float --
getting that wrong the other way would be worse than the current error.

Found by `.todo/620`: `practicals-1.0.3/Chapter23/spam.lisp` opens with

```lisp
(defparameter *max-ham-score* .4)
(defparameter *min-spam-score* .6)
```

and the whole chapter fails to load. Patching those two literals makes the
chapter byte-identical to SBCL.

`LispLexer`'s number scan is the only place to change, and the change is
frontend-only -- no backend sees a literal that has already been read. Watch
the boundary against the symbol `.` (the dotted-pair marker), against `...`,
and against a bare `-`/`+`, all of which must stay symbols. Pin in
`LispReaderTest` beside the existing float cases, and add a ci-spec row so all
four backends read the same text.

Related: `.todo/037` (the numeric tower's missing FUNCTIONS -- a different gap;
this one is purely the reader).
