# i97. generic-cl on the compile paths

Difficulty: High

`(ql:quickload :generic-cl)` loads on the interpreter (and a generic call runs through
static-dispatch's compiler macro); the JVM compile of a program that quickloads it stops.
Measured 2026-11-07, JVM, program `(ql:quickload :generic-cl) (print (generic-cl:+ 1 2))`:

1. `WARNING: library top-level form failed at compile time (skipped): The function
   %DEFINE-METHOD-COMBINATION is undefined` -- static-dispatch's `combinations.lisp`
   (`define-method-combination%`) replayed into the macro-time evaluator.
2. `error: No such package: STATIC-DISPATCH.METHOD-FUNCTIONS` -- static-dispatch's
   `common.lisp:79` makes the package at load time,
   `(defvar *method-function-package* (make-package 'static-dispatch.method-functions))`, and its
   `defmethod`/`defgeneric` expansions `gentemp` method-function names in it at macro time. The
   compilers' resolver knows only read/compile-time packages (`.kb/packages.md`, "Runtime tier"),
   so a symbol the macro-time evaluator interned in a run-time package reaches them unknown.

Decide for 2 whether a package the macro-time evaluator made while compiling joins the compile-time
tier (it is what SBCL's compile-file image holds), then measure what stops next. Done when the
program above prints `3` on the JVM, and on WASM if the rest compiles there.
