# j02. A `defvar` below the top level on the compile paths

Difficulty: Medium

The compile paths decide a `defvar`'s "bind only if unbound" at compile time
(`Jvm/WasmDefvarCompiler`, `ctx.definedGlobals`), which holds for straight-line top-level forms
only. Measured 2026-11-07 (found working i91; the JVM, P1 and the component agree, the
interpreter and SBCL 2.2.9 answer the second column):

| form | compiled | SBCL |
|---|---|---|
| `(when nil-at-run-time (defvar *a* 1)) (defvar *a* 2)` | `*a*` unbound, read as `NIL` | `2` |
| `(dotimes (i 2) (defvar *b* (list i)))` | `(1)`, stored twice | `(0)` |
| `(defun f () (defvar *c* :inner)) (defvar *c* :outer) (f)` | `:INNER` | `:OUTER` |
| `(defun g () (defvar *d* 2)) (g) (boundp '*d*)` | `NIL` | `T` |
| `(defun h (x) (defvar *e* x)) (h 1) *e*` | `Cannot compile symbol reference: *E*` | `1` |

A `defvar` in a function body stores a LOCAL of that function (`JvmDefvarCompiler`'s
non-global arm): `GlobalVarCollector` does not count it as a global.

Plan: a global any non-straight-line `defvar` with a value names becomes a global
(`GlobalVarCollector`) whose variable starts as the UNBOUND marker (`.kb/compile-time-boundp.md`,
"A probe the fold leaves open"), and every `defvar` of it stores only when the variable holds the
marker -- expressed as `(if (boundp 'x) nil (setq x init))` after the folds, or in the two
`defvar` compilers. CompileTimeBoundp already poisons a name a deferred `defvar` binds.

Done when the table's forms answer SBCL's on the JVM and both WASM backends (a ci-spec case),
and a program whose every `defvar` is a top-level one compiles byte-identically.
