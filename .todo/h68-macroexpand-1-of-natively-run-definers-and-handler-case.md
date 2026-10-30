# h68. `macroexpand-1` leaves natively-run CL definers and `handler-case` unexpanded

Difficulty: Medium

`macro-function` is non-nil for these CL macros, but `macroexpand-1` answers the form
unchanged with `expanded-p` nil, because the evaluator and the compilers run them as special
forms with no form-level expansion: `handler-case`, `defun`, `defmacro`, `defvar`,
`defparameter`, `defconstant`, `defclass`, `defgeneric`, `defmethod`, `defstruct`, `defsetf`,
`defpackage`, `define-symbol-macro`, `in-package` (measured on the interpreter 2026-10-30).
A walker that trusts `macro-function` (iterate) warns "a macro that won't expand" and does not
walk into them; on the compiled backends a COMPUTED `macroexpand-1` of one signals.

Goal: give each an expansion in `LispMacroExpander.expandNativeMacro` that runs on all four
backends at native cost (a walker emits it), or decide per name that none exists and record why
in `.kb/symbol-runtime-api.md`. `handler-case` first: it is the one likely inside an `iter`
body; CL's expansion goes through `handler-bind` + a `go` out of the handler lambda, so measure
the compiled cost before choosing it.
