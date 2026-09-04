# Lisp-2 (separate function/variable namespaces) in all three backends

- A bare symbol is a VARIABLE reference only (interpreter: `The variable X is unbound`;
  compilers: `Cannot compile symbol ...`).
- A symbol in call position resolves in the FUNCTION namespace only: a `let`-bound `car` never
  shadows the function `car`.
- A function value comes from `(function name)` / `#'name` (a `function` special form; the lexer
  emits `Token.FunctionQuote` for `#'`) or `symbol-function`.
- `funcall`/`map`/`reduce` accept symbol designators -- interpreter: at runtime via `apply`;
  compilers: `compiler.FunctionDesignators.normalize` statically rewrites a literal
  `(quote name)` in function position to `(function name)`, and `.literalName` reads it back so
  `funcall` / the map family / `reduce` / `sort` emit the DIRECT call instead of dispatching
  through a function value (`.kb/optimize-dead-code-elimination.md`).
- `defun` defines into the function namespace and returns the name symbol.

Interpreter: `Environment` keeps two maps (`lookup`/`define`, `lookupFunction`/`defineFunction`;
builtins use `defineFunction`); `LispEvaluator.SPECIAL_OPERATORS` (=
`PackageRegistry.specialOperatorNames()`) lists names with no function value, so `#'if` errors.

Compilers: pass 1 collects only real `(defun ...)` -- a top-level `(setq f (lambda ...))` binds a
VARIABLE, called via `funcall`. `Jvm/WasmFunctionFormCompiler` compiles
`(function name)`/`symbol-function`. Eval runtimes keep a second function namespace (`_fenv`
field on JVM, `GLOBAL_FENV` wasm global). `FreeVarAnalyzer` skips the operator position and
`(function name)` designators.
