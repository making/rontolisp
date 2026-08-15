# A `#'`-reference of the print family drops its STREAM argument (the JVM writes to stdout, WASM traps)

Difficulty: Low

`(funcall #'princ 'foo s)` -- the print family used as a first-class VALUE with
the optional stream -- works on the interpreter and is broken on all three
compiled backends:

```lisp
(princ (with-output-to-string (s) (funcall #'princ 'foo s)))
```

- interpreter: `FOO` (the capture holds it)
- JVM: the capture is EMPTY and `FOO` goes to standard output
- WASM Preview 1 / `--component`: `wasm trap: unreachable`

The cause is the wrapper defun the compile paths build for a `#'`-reference:
`BuiltinFunctionWrappers.WRAPPER_DEFS` registers `print` / `prin1` / `princ`
with `unary(...)`, i.e. `(defun princ (a) (princ a))`, so a second argument is
dropped (JVM) or fails the arity check (WASM). The one-argument call
`(funcall #'princ 'foo)` is fine everywhere.

The fix is presumably `unaryOptionalSecond(...)` -- the helper right beside
`unary` in the same file, already used for exactly this shape -- but the sweep
is the work: every first-class member of the output family with an optional
stream (`print`/`prin1`/`princ`/`terpri`/`fresh-line`/`write-line`/
`write-string`/`force-output`/`finish-output`/`clear-output`/`read-line`/
`read-char`/`peek-char`/`listen`), checked one by one against what the
interpreter's `Environment` function accepts. An OMITTED stream and an explicit
`nil` are the same designator (`.kb/standard-output-redirect.md`), so the
optional's nil default needs no branch of its own.

Found while landing `*print-case*` (`.todo/041`, 2026-08-15) -- the case route
had to be added to the interpreter's function VALUES as well as to the operator
seam, which is what put a `(funcall #'princ 'foo s)` differential on the table.
It is NOT a `*print-case*` regression: the divergence reproduces with the
variable untouched.

## Acceptance

- The program above prints the same text on all four backends.
- A `JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest` case per backend,
  and a `ci-spec.yaml` row for the family (the driver already captures stdout,
  so a `with-output-to-string` round trip is the assertion).
