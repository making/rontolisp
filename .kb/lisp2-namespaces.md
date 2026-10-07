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
  through a function value (`.kb/optimize-dead-code-elimination.md`). A quoted name with no
  definition is NOT rewritten (`.kb/error-handling.md`, "Undefined functions keep the call-time
  stub contract").
- `defun` defines into the function namespace and returns the name symbol.

Interpreter: `Environment` keeps two maps (`lookup`/`define`, `lookupFunction`/`defineFunction`;
builtins use `defineFunction`); `LispEvaluator.SPECIAL_OPERATORS` (=
`PackageRegistry.specialOperatorNames()`) lists names with no function value, so `#'if` errors.

Compilers: pass 1 collects only real `(defun ...)` -- a top-level `(setq f (lambda ...))` binds a
VARIABLE, called via `funcall`. `Jvm/WasmFunctionFormCompiler` compiles
`(function name)`/`symbol-function`. Eval runtimes keep a second function namespace (`_fenv`
field on JVM, `GLOBAL_FENV` wasm global). `FreeVarAnalyzer` skips the operator position and
`(function name)` designators.

## A native built-in's function value

**Invariant: every `cl` function but `require`/`provide` and the four user-defined generics
(`.todo/d94`) is a function value on every compiled target** (`StandardFunctionValueCompileTest`
compiles `#'name` of all of them in one program). The catalog (`BuiltinFunctionWrappers.WRAPPER_DEFS`)
covers what the interpreter lowers in `evalCons`; the NATIVE built-ins it binds as Java
`LispFunction`s and the compilers lower in call position only (`NativeCallShapes`) had none, and
`#'arrayp` refused the program (measured 2026-10-07: 28 names, `array-dimensions arrayp close eval
export fdefinition fmakunbound get-internal-real-time get-internal-run-time
get-output-stream-string get-universal-time hash-table-rehash-size hash-table-rehash-threshold
hash-table-size hash-table-test import load make-random-state make-string-input-stream
make-string-output-stream make-synonym-stream open-stream-p rationalp row-major-aref
symbol-function unexport unuse-package use-package`; JVM, P1 and component identical).

- `BuiltinFunctionWrappers.NATIVE_VALUE_FUNCTIONS` names them; `FunctionValueWrappers`, which
  both compilers call for every injected wrapper, derives each value from the operator's
  `NativeCallShapes` row (`nativeValue`). It sits ABOVE the catalog: the bodies are built by
  `BuiltinCallArity` and `ShadowedBuiltins`, which read the catalog, so building them inside it
  is a class cycle (`PackageCycleTest`). The value: the required parameters, and where the row takes more,
  `ShadowedBuiltins.tailDispatch` over a `&rest` tail -- one direct call per admitted count, the
  interpreter's wrong-count text for the rest, `close`'s `:abort` checked. The shape stays the
  row's, so the catalog and the native table stay disjoint (`BuiltinCallArity.buildShapes`).
  `load` and `make-string-output-stream` read a keyword tail with `getf` (an injected wrapper
  cannot carry a `&key` check: its `%LL-*` helpers are spliced before it exists), so an unknown
  keyword passes where the interpreter rejects it.
- `make-string-input-stream`, `make-string-output-stream` and `make-synonym-stream` joined
  `NativeCallShapes` for it: the interpreter's `1 to 3 arguments` text became the shape's
  (`at least 1` / `at most 3`), on a direct call too.
- All are REFERENCE-GATED: the bodies reach runtimes a source scan turns on by the operator's
  name, and a program that takes none as a value is untouched. The scans that see the body
  only after the gates were taught the reference: `usesRuntimeFunctionBox` counts
  `#'symbol-function`/`#'fdefinition`, `callsLoadWithIfDoesNotExist` counts `#'load`/`'load`
  (the `probe-file` splice), wasm's `usesEval` counts `makesComputedSynonymStream` -- whose
  computed arm traps on P1 and the component even in call position before 2026-10-07.
- `FunctionDesignators.normalizeBuiltinDesignators` rewrites a literal
  `(symbol-function 'name)` / `(fdefinition 'name)` of a reference-gated name to `#'name`
  (not a `setf`/`psetf` place, not under a local binding), so it injects the wrapper: it
  compiled to `The function FORMAT is undefined` for `format`.
- `#'require` / `#'provide` refuse with the computed call's text, `REQUIRE is only supported as a
  literal top-level form on the compile path` (`BuiltinFunctionWrappers.noFunctionValueMessage`).
- `--component`'s sockets rewrite (`WasmSocketsRewrite`) runs before the wrappers exist, so
  `#'close` of a socket stream skips its `%io-close` bookkeeping, as `#'listen` does.
