# i16. `(setf (macro-function s) v)` in a function body is a compile error

Difficulty: Medium

Measured 2026-11-04. The top-level and macro-generated forms work on the compile paths; inside a
function body the form stays a compile error, and parachute's `fixture.lisp:31` stops on it (`.todo/152`).

Decide first: an error at the call, or evaluate the value and return it. An error at the call breaks
a form that runs at compile time and also stays in the program, as rutils' `abbr` does.
