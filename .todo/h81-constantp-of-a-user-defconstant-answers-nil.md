# h81. `constantp` of a user `defconstant` name answers NIL

Difficulty: Medium

```lisp
(defconstant +a+ 1)
(constantp '+a+)   ; NIL, CLHS and SBCL: T
```

`.kb/read-time-constants.md` "`constantp`": only the standard constant names are recognized.

Consequence: `alexandria:define-constant` evaluated a SECOND time for the same name signals
`+Y+ is an already bound non-constant variable` (`%reevaluate-constant` tests `constantp`).
cl-colors2 defines `+yellow+` (and others) in more than one colour-name file, so
`(ql:quickload "cl-ansi-text")` stops there. Repro without cl-colors2: two identical
`(alexandria:define-constant +y+ (make-rgb :r 1) :test #'equalp)`.

Fix on all four backends (the literal-quote fold in `macro/expandConstantp` and the run-time
function both need to know user constants). Then re-probe cl-ansi-text (`(cl-ansi-text:red
"hello")` -> SBCL prints `"^[[31mhello^[[0m"`).
