# Compiled direct calls of built-ins ignore or crash on a wrong argument count

Difficulty: Medium

Found 2026-09-26 while making the compiled `eval` report wrong counts (`.kb/eval-runtime.md`,
"Argument counts"). A DIRECT call of a built-in with the wrong number of arguments, in compiled
code outside `eval`:

| form (in a defun) | interpreter | JVM / wasm |
|---|---|---|
| `(car x 2)`, `(first x 2)`, `(length x 2)` | `CAR expects 1 argument, got 2` (program-error) | the surplus is dropped, the call answers |
| `(nth x)`, `(cons x)` | `NTH expects 2 arguments, got 1` | compile error `Index 2 out of bounds for length 2` |

The shared call-position expansions (`LispMacroExpander.expandFirst`/`expandNth`/..., the
backends' lowering switches) index the argument list without checking its length. The interpreter
now declines to expand a call of another shape (`LispEvaluator`, `properLength`), so its built-in
reports.

Goal: the interpreter's program-error at run time on both compiled backends, the text from
`ClosRegistry.arityMessage` named by `BuiltinFunctionWrappers.arityOperator` -- the catalog's
lambda lists already know every wrapped operator's shape, so one front-end check in
`CompileFrontend.expand` (lowering a wrong-count call to a call-time signal) may cover every
lowering instead of a guard per site. A compile-time warning beside it, as an undefined function
gets. Pin in `ci-spec.yaml`.
