# Local function and macro definition (`flet`, `labels`, `macrolet`, `symbol-macrolet`)

**Status:** `flet`/`labels` DONE 2026-07-05 (expansion route, all backends; see
`.kb/flet-labels.md`). Remaining: `macrolet`/`symbol-macrolet` below.

## What's missing

RontoLisp has `defun` (top-level function definition) but no way to define functions or macros locally within a lexical scope.

### Missing operators

| Operator | Kind | Purpose |
|----------|------|---------|
| `flet` | Special form | Local function definitions (non-recursive): `(flet ((f (x) (+ x 1))) (f 2))` |
| `labels` | Special form | Local function definitions (recursive): functions can call each other |
| `macrolet` | Special form | Local macro definitions: `(macrolet ((m (x) x)) (m 1))` |
| `symbol-macrolet` | Special form | Local symbol macros: `(symbol-macrolet ((y (car x))) y)` |

### Design considerations

- **`flet`**: Each name binds in the function namespace for the body. Since RontoLisp is Lisp-2, `flet` only affects the function namespace (call position and `funcall`), not the variable namespace.
- **`labels`**: Same as `flet` but the functions can reference each other (mutual recursion). The function values must be constructed before any body runs.
- **`macrolet`**: Macros expand at read/compile time. `macrolet` needs to be handled by the macro expander (or a pre-expansion pass). In the interpreter, it's an `evalCons` case that adds macros to the environment. In compilers, it needs to be handled before the main macro expansion pass.
- **`symbol-macrolet`**: Expands symbol references (not calls) to forms. Needs a rewrite pass on the body before evaluation/compilation.

### Implementation approach

1. **Interpreter**:
   - `flet`/`labels`: Add function-local environment layer in `Environment`.
   - `macrolet`: Add macro-local environment layer; expand in `evalCons`.
   - `symbol-macrolet`: Rewrite pass on body forms.

2. **JVM compiler**:
   - `flet`/`labels`: The function value representation already supports closures. Need to emit local function definitions and register them in a local function namespace.
   - `macrolet`: Expand at compile time before the main compilation.
   - `symbol-macrolet`: Rewrite at compile time.

3. **WASM compiler**:
   - Similar to JVM but with WASM function indexing.

### Complexity

- `flet` is straightforward (lexical function binding).
- `labels` requires fixed-point construction of function values (all must exist before any runs).
- `macrolet` is the hardest for compilers (expansion timing).
- `symbol-macrolet` is a rewrite pass (scoping is the tricky part).

### Related

- `[[31-lambda-list-extensions]]` (local functions use lambda lists)
- `[[32-multiple-value-system]]` (local functions may return multiple values)
