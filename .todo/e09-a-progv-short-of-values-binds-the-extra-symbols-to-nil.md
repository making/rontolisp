# e09. A progv short of values binds the extra symbols to nil instead of leaving them unbound

Difficulty: Medium

```lisp
(defvar *pa* 1)
(defun pa-read () (handler-case *pa* (unbound-variable (c) (list :unbound (cell-error-name c)))))
(print (progv '(*pa*) '() (list (pa-read) (boundp '*pa*))))
(print (pa-read))
```

SBCL 2.2.9 prints `((:UNBOUND *PA*) NIL)` then `1`; the interpreter, the JVM, Preview 1 and the
component print `(NIL T)` then `1` (measured 2026-10-07). Every backend binds a symbol `progv`
has no value for to nil -- the interpreter's `evalProgv`, the compile paths' `%progv-dyn-bind`
-- and `doc/*/reference/special-forms/progv.md` documents it.

## Plan

- Interpreter: bind such a symbol to an unbound state for the extent, which a read of it
  signals (`unbound-variable` naming it) and `boundp` answers nil for, the global restored
  after.
- Compile paths: bind the UNBOUND marker (`.kb/dynamic-special-variables.md`, "A read of a
  special without a value"). Any special a `progv` can name may then hold it, and in a program
  that calls `progv` that is every special (`collectDynamicallyBound`), so every read of every
  special needs the `_bound` / `emitCheckedRead` check, the valued ones included, and `boundp`
  of each would read its variable: the price set against keeping the documented divergence.
- The change pins on all four backends together, with the docs (`progv.md`) in the same
  commit.
