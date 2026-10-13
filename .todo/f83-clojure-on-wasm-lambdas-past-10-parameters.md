# f83. Clojure on wasm: lambdas past 10 parameters (dynaload's `IFn` invokes)

Difficulty: High

Found closing `.todo/f66` (2026-10-13): malli 0.20.3 loads and its probe is the
oracle's line for line on the interpreter and the JVM, but the wasm build stops at
`Cannot compile lambda: the WASM backend supports at most 10 parameters, got 11`.
The lambdas are borkdude/dynaload 0.3.5's `LazyVar` reify `invoke` arities 10-20
(`[this a b c d e f g h i j]` and up); dynaload alone reproduces it, so it is a
backend limit, not a malli shape.

## What decides the design

- `WasmArityBundler` already rewrites too-wide top-level `defun`s into the "bundle
  the extra arguments into a list" shape (its docblock measures why the limit
  itself cannot move). Lambdas (reify method bodies, `fn` values, `labels`) do not
  ride it: their callers pass positionally, and first-class call targets dispatch
  per arity. Extending the bundling (or the per-arity dispatch ceiling) to lambdas
  is the fix; the `widestDispatchArity`/`spreadOverArityFuncalls` machinery names
  the moving parts.
- Out of scope: host calls at load (`.todo/f84`, same library's next stop).

## Plan

1. A dynaload-only wasm build (the f66 probe) compiles; the malli probe from f66
   still stops at the host calls of f84, not here.
2. clojure-spec rows where the behavior is observable, four backends.
