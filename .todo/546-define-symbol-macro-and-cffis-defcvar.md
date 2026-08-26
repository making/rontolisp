# 546. `define-symbol-macro`, and with it cffi's `defcvar`

Difficulty: Medium

`symbol-macrolet` exists (`LispNames.SYMBOL_MACROLET`, expanded at macro time);
`define-symbol-macro` -- its global, top-level sibling -- does not. Every consumer of the
gap is the same shape: a library defines a NAME that reads and writes something that is
not a variable, and spells it as one.

The one that surfaced it: **`cffi:defcvar`**, the way every C binding reads a global C
variable (`*errno*`, `environ`, a library's `*version*`). `functions.lisp` loads and the
macro expands fine; the expansion then calls `define-symbol-macro` and the form dies with
`The function CFFI::DEFINE-SYMBOL-MACRO is undefined`. Today the workaround in
`doc/*/guides/cffi.md` is `cffi:foreign-symbol-pointer` + `cffi:mem-ref`, which is the
same read spelled out -- correct, and not what a binding's source says.

## What it is

`(define-symbol-macro name expansion)`: from then on, a reference to `name` in a value
position is the expansion, and `(setf name v)` is `(setf expansion v)`. It is NOT a
variable -- `symbol-value` does not see it, `let` on the name is an error in CL -- and it
is global and top level, which is exactly what makes it a different problem from
`symbol-macrolet`:

- the interpreter needs a global symbol-macro table the variable-reference path consults
  after the lexical environment and before the global one, and `setf` has to expand
  through it;
- **both compilers** need the same table at COMPILE time, because a reference in a later
  top-level form has to see a definition from an earlier one -- the `defmacro` /
  `UserMacroExpander` route, not a runtime one. A definition that is not top level, or
  whose name is computed, is a compile error the way `defmacro`'s is;
- `LispMacroExpander` is where the expansion belongs, so one implementation serves the
  interpreter and both backends.

## Acceptance

`(define-symbol-macro *x* (aref buf 0))` reading and `setf`-ing on all four backends
(a `ci-spec.yaml` case), `cffi:defcvar` over a real C global on the interpreter
(`CffiSystemTest`), the docs page under `reference/special-forms/`, and the cffi guide's
"what does not work" row deleted rather than reworded.
