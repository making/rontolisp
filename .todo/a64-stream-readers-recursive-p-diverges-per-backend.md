# The stream readers' recursive-p argument: accepted, refused or a failed compile per backend

Difficulty: Medium

Measured 2026-09-27 over `(with-input-from-string (s "x") FORM)`:

| form | interpreter | JVM / wasm-GC |
|---|---|---|
| `(peek-char nil s nil :eof nil)` | `simple-error` `PEEK-CHAR expects 0 to 4 arguments` | warns, then `PEEK-CHAR expects 0 to 4 arguments, got 5` at run time |
| `(funcall #'peek-char nil s nil :eof nil)` | the same `simple-error` | `#\x` |
| `(read-char s nil :eof nil)` | `#\x` | the COMPILE fails: `read-char expects 0 to 3 arguments, got 4` |
| `(read-line s nil :eof nil)` | `"x"` | the COMPILE fails: `read-line expects 0 or 1 arguments, got 4` |
| `(read-char-no-hang s nil :eof nil)` | `#\x` | warns, then `READ-CHAR-NO-HANG expects 0 to 3 arguments, got 4` |
| `(read s nil :eof nil)` | `X` | `X` |

`recursive-p` is in each operator's standard lambda list, and `compiler/BuiltinCallArity`'s shape
admits it (the `peek-char` wrapper takes five), so the direct-call count check lets these calls
through to lowerings and a Java body that are narrower. Ignoring `recursive-p` is conforming here
(it only matters to reader-macro recursion).

Goal: all four accept `recursive-p` in call position and through the function value for
`peek-char`, `read-char`, `read-char-no-hang`, `read-line` (check `read-byte`, `unread-char`,
`listen` and the Gray-stream rewrites of the same names too), with a `LispEvaluatorTest` sweep
that fails when a shape admits a count the interpreter's body or a lowering refuses.
