# Count a macro-time `(warn ...)` under `--warnings-as-errors`

Difficulty: Medium

`--warnings-as-errors` counts the compiler's own warnings only (`.kb/compile-warnings.md`).
A `(warn ...)` that Lisp code signals while a macro expands on the compile path is printed by
the macro-time evaluator as `WARNING: ...` and never counts:

```console
$ printf '(defmacro m (x) (warn "m got ~a" x) x)\n(print (m 1))\n' > mw.lisp
$ rontolisp mw.lisp -o mw.class --warnings-as-errors   # exit 0
WARNING: m got 1
```

SBCL counts it: a WARNING (not a STYLE-WARNING) signaled during `compile-file`, macroexpansion
included, sets `failure-p`.

## To decide

- Where an unhandled macro-time `warn` reaches its default report on the compile path, and how
  it reaches `CompileWarnings` (`eval` may import `compiler`).
- Attribution: the macro call form being expanded (`UserMacroExpander`), so a library macro's
  warning inside library code stays uncounted while one at a user's call site counts.
- `style-warning` should not count (SBCL's rule); a muffled warning must not count.
- Whether the printed line changes (`WARNING:` vs `file:line:column: warning:`).

## Verification

- A user macro's `(warn ...)` at a call in the program fails the compile under the option.
- A `style-warning`, a muffled warning and a library macro's warning in library code do not.
- Update `doc/*/compiling/warnings.md` (the "never count" list) and `.kb/compile-warnings.md`.
