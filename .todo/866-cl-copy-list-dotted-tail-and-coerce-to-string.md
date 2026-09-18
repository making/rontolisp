# Common Lisp `copy-list` of a dotted list, `coerce` of non-characters to `string`

Difficulty: Medium

Found while fixing the Scheme `list-copy` / `string` rows (`.todo/862`), which now go
through their own `scheme.lisp` helpers. The Common Lisp functions stay wrong, measured
2026-09-18 on the interpreter, the JVM (`-o E.class`) and wasm (`-o e.wasm`):

| Form | interpreter | JVM | wasm | ANSI |
|---|---|---|---|---|
| `(copy-list '(1 2 . 3))` | `(1 2)` | `ClassCastException` (Long to Object[]) | trap: cast failure | `(1 2 . 3)` (CLHS `copy-list`: a dotted list copies dotted) |
| `(coerce (list #\a 1) 'string)` | `"a1"` | `"a1"` | `"a1"` | a `type-error` |

Where:

- Interpreter: `Environment` `COPY-LIST` collects the cars and drops the final atom.
- JVM and wasm: `LispMacroExpander.expandCopyList` rewrites `(copy-list x)` as
  `(append x nil)`, and `append` of a dotted non-last argument fails.
- `coerce` to `string`: all three accept any element and print it.

Fix both on every backend together with a `ci-spec.yaml` case (a native E2E run is then
due, `.kb/running-backends.md`). Measure the size and speed of `copy-list` before and after
on the compiled backends -- `(append x nil)` is a native path today, and a loop that keeps
the tail must not cost a program that only copies proper lists.
