# Scheme: applying `#f` reports "The function #f is undefined"

Difficulty: Medium

Left by `.todo/844` (2026-09-17), which unified the non-function error on all four
backends and removed the Scheme REPL's rewording (`#f is not a procedure; operands: (2 3)`).
The false value is the symbol `|#f|` (`.kb/scheme-frontend.md`), so `((if #t #f car) 1)`
now reports `The function #f is undefined` -- an `undefined-function` about a NAME, when
the program applied a VALUE that is not a procedure. The same holds for the unspecified
value `|#!unspecific|`. A Scheme reader is told something false about their program.

Constraint from `.todo/844`: no backend may learn a Scheme name, and the interpreter REPL,
file mode and the compiled backends must print one text.

## To do

1. Failing `scheme-spec.yaml` case (all four backends): applying `#f`, the unspecified
   value, and a number; expected text is a "not a procedure"-shaped report naming the value
   as Scheme prints it.
2. Find a representation-level answer rather than a rewording: e.g. mark the reserved
   value symbols so the shared apply path classes them as non-functions (a property the
   front end sets, not a name the backend knows), or lower Scheme calls whose operator is
   not a known procedure through a check -- measure size/time on `hello` and a
   20M-iteration `funcall` loop against `.kb/error-handling.md`'s numbers before choosing.
3. Record the decision and numbers in `.kb/scheme-frontend.md` and `.kb/error-handling.md`.
