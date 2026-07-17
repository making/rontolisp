# Lambda list extensions (`&optional`, `&rest`, `&key`, `&aux`, `&whole`, `&allow-other-keys`)

**Status:** mostly DONE (2026-07-03). `&optional`, `&rest`, `&key`,
`&allow-other-keys`, and `&aux` work in `defun`/`lambda` on all four backends
(interpreter, JVM, WASM Preview 1, WASM component), including `funcall`/`apply`/
`mapcar` and calls from the runtime `eval`. Implementation notes:
`.kb/lambda-lists.md`; user docs: `doc/en/reference/special-forms/defun.md`.

## Remaining follow-ups

| Item | Notes |
|------|-------|
| `&whole`/`&environment` | Not implemented (rejected with an error), including in `defmacro`/`destructuring-bind` patterns. |
| ~~`defmacro` extended lambda lists~~ | DONE 2026-07-05: destructuring parameter lists, `&optional`, `&key`, `&aux` via the `destructuring-bind` wrapping in `LispEvaluator.evalDefmacro` (`.kb/defmacro-backquote.md`). Lite semantics: no pattern-mismatch error (plain lambda lists keep the strict arity check). |
| Runtime-`eval` lambdas | A `lambda` living inside a quoted form evaluated by the compiled `eval` binds positionally; the emitted interpreters (`Jvm/WasmEvalRuntimeBuilder` `_apply` interpreted-closure branch) do not parse `&` keywords. Documented in `doc/en/guides/eval-limitations.md`. |
| `--no-gc` | Lambda-list keywords are a compile error under `--no-gc` (rest list is a cons; the scalar lowering has none). |
| Variadic `BuiltinFunctionWrappers` | `#'+`, `#'list`, etc. still pin one arity as first-class values. They could now be `(&rest r)` wrappers, but each change shifts the wrapper's physical arity (dispatch membership, eval registry) and cross-backend output, so do it deliberately with ci-spec coverage. |
| funcall/apply >7 args | Dispatchers exist for arities 0..7 only; a variadic function called through funcall/apply with more than 7 total arguments is unsupported (direct calls are unlimited). |

### Related

- `[[32-multiple-value-system]]` (multiple values often paired with `&whole`)
- `[[34-local-function-definition]]` (`flet`/`labels` use lambda lists too)
