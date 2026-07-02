# Lambda list extensions (`&optional`, `&rest`, `&key`, `&aux`, `&whole`, `&allow-other-keys`)

**Status:** not implemented. HIGH priority — without these, every `defun` is fixed-arity with no flexibility.

## What's missing

Currently `lambda` and `defun` accept only a plain parameter list `(a b c)` — fixed arity, no defaults, no rest, no keywords. The `LispLambda` record holds `List<LispVal> params` (bare symbols) and the evaluator/compilers check exact arity.

### Missing lambda list keywords

| Keyword | Purpose | Difficulty |
|---------|---------|------------|
| `&optional` | Parameters with defaults, e.g. `(defun f (x &optional (y 0)) ...)` | Medium |
| `&rest` | Variable tail, e.g. `(defun f (&rest xs) ...)` | Medium |
| `&key` | Keyword arguments, e.g. `(defun f (&key (x 0)) ...)` | Hard |
| `&aux` | Auxiliary (local) variables | Easy |
| `&whole` | Bind the entire argument list | Easy |
| `&allow-other-keys` | Suppress unknown-key error | Easy (with `&key`) |

### What needs to change

- **Reader/AST**: `LispLambda` needs richer structure (or the params list carries the lambda-list metadata).
- **Interpreter** (`LispEvaluator.evalLambda`): Currently matches param count exactly. Needs to handle optional defaults, collect rest into a list, and parse keyword args.
- **JVM compiler** (`JvmLambdaCompiler`): Emits fixed-arity dispatch. Needs runtime argument list construction for `&rest`/`&optional`/`&key`.
- **WASM compiler** (`WasmLambdaCompiler`): Same.
- **`--no-gc` scalar WASM**: The export boundary is already fixed-arity; internal lambdas with extensions should work once the core supports them.
- **`BuiltinFunctionWrappers`**: Wrappers that use `&rest`/`&key` need the lambda body to work.

### Implementation approach

1. Extend `LispLambda` (or add a companion structure) to represent the parsed lambda list.
2. Implement in interpreter first (`evalLambda` argument binding).
3. Thread through JVM and WASM compilers.
4. Many existing built-ins (`+`, `-`, `list`, `cons`, `format`, `loop`, etc.) are already variadic at the builtin level — `&rest` in user `defun` is the new piece.

### Related

- `[[32-multiple-value-system]]` (multiple values often paired with `&whole`)
- `[[34-local-function-definition]]` (`flet`/`labels` use lambda lists too)
