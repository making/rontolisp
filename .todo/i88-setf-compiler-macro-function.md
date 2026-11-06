# i88. `(setf compiler-macro-function)`

Difficulty: Medium

Not implemented (`.kb/compiler-macros.md` lists it). Measured 2026-11-06, interpreter:

    (setf (compiler-macro-function 'f) (lambda (form env) ...))
    => setf does not support place: COMPILER-MACRO-FUNCTION

Consumers: static-dispatch 20211209 (`src/common.lisp:294`) and cl-environments 20250622
(`src/walker/cl-overrides.lisp:194`, which forwards to `cl:compiler-macro-function`), so
generic-cl. Measured 2026-11-06 once cl-environments' walker read with `:cl-environments-full`:
cl-environments, cl-form-types and static-dispatch load on the interpreter and
`(ql:quickload :generic-cl)` stops with the error above; the JVM compile of cl-environments stops
with it at `cl-overrides.lisp:194:2`.

Behavior: install a compiler macro from a function value; `nil` removes it; `compiler-macro-function`
answers it. On the compile paths a top-level `setf` of a literal name with a `lambda`/`#'f` value is
the static case (register like `define-compiler-macro`); a run-time one only has to be answered by
`compiler-macro-function` and `funcall` (compiled code is already compiled). Every backend with its
pinning case (`makunbound-and-compiler-macro-function` is the natural one to extend).
