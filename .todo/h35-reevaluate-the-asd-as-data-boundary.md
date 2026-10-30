# h35. Re-evaluate the `.asd`-as-data boundary: four non-portability libraries are refused by it

Difficulty: High

`.kb/asdf.md` ("The `.asd`-as-data boundary is not what stops portableaserve") says
**re-evaluate when something OTHER than a per-implementation compatibility layer is refused
by that gate**. Measured 2026-10-30, four ordinary libraries are:

| library | top-level `.asd` form refused |
|---|---|
| `cl-csv` | `(eval-when (...) (unless (find-package :cl-csv.system) (defpackage :cl-csv.system (:use :common-lisp :asdf))))` then `(in-package cl-csv.system)` |
| `function-cache` | the same idiom (`function-cache-system`) |
| `cl-containers` | `(unless (or (member :asdf-system-connections *features*) (find-system 'asdf-system-connections nil)) (warn ...))` and `(when ... (load-system "asdf-system-connections"))` |
| `cl-pdf` | `(when (notany #'(lambda (f) (find f *features*)) '(:use-salza2-zlib ...)) (push :use-... *features*))` |

(`cl-store`'s `#-(or lispworks ... sbcl ...) (error ...)` is an implementation guard and does
not count.)

Measured: with cl-csv's two leading forms deleted, it proceeds to its `cl-interpol`
dependency (named-readtables: h31 item 2, h53).

## Decide

1. **Narrow:** add these shapes to the accepted list. That covers a conditional `defpackage`,
   a `warn`, a `find-system`/`load-system` of an optional system, and a computed feature
   push. It grows by one idiom per library.
2. **Essential:** evaluate the `.asd` as Lisp (the interpreter already runs at compile time
   inside the compiler) with `defsystem` as the recording operator and `find-system`/
   `load-system`/`*features*`/`warn` live. The data parse would remain only for the browser
   playground's no-evaluator case. This changes the "deny by default" stance and the
   `#.`/feature-announcement machinery.

Measure startup and determinism of (2) on the existing loadable corpus before choosing.
Record the decision and numbers in `.kb/asdf.md`.
