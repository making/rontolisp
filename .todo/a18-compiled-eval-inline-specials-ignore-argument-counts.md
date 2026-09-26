# The compiled eval's inline specials ignore argument counts and extra lists

Difficulty: Medium

Found 2026-09-26 while making a registered function's wrong count report inside a compiled
`eval` (`.kb/eval-runtime.md`, "Argument counts"). The operators `_eval` handles INLINE never
reach the spread dispatcher's count guard:

| form inside `eval` | interpreter | JVM / wasm |
|---|---|---|
| `(funcall)` | `FUNCALL expects at least 1 argument` (simple-error) | JVM: `Cannot load from object array because "<local2>" is null` |
| `(mapcar #'car)` | `MAPCAR expects at least 2 arguments, got 1` | JVM: the same NPE text |
| `(mapcar #'+ '(1 2) '(10 20))` | `(11 22)` | `(1 2)` -- only the first list is walked |
| `(first 1 2)`, `(first)`, `(nth 1)` | type-error on `1` / `Index 1 out of bounds for length 1` | `the value is not of the expected type` / the NPE text |
| a compiled `defun` of 8+ parameters | called | JVM: `nil` (`_lookup` filters `paramCount > MAX_CALLABLE_ARITY`); wasm: 11+ traps (cast failure) |

The interpreter's own `first`/`nth`/`rest` texts are not a count report either: fix it there
first (`FIRST expects 1 argument, got 2`), then give the inline arms a count guard naming the
operator (JVM `_arityChk` with a `JvmArityOperators` shape; wasm `_arity_chk` with the wrapper's
funcId, as `emitComparisonChain` does), or drop an arm whose wrapper already does the job
(`mapcar`/`mapc` are variadic wrappers). Pin in `ci-spec.yaml`.
