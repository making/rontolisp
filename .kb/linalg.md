# The `linalg` package (numpy-style vector/matrix operations)

One hand-written Lisp-source library, `src/main/resources/am/ik/rontolisp/eval/linalg.lisp`,
following the `json.lisp` pattern (see `json.md`) so a single implementation
runs identically on all backends. 33 exported functions (constructors `zeros`/
`ones`/`full`/`eye`/`arange`/`linspace`/`from-list`, shape ops, broadcasting
`add`/`sub`/`mul`/`div`/`emap`, products `dot`/`matmul`/`outer`, reductions,
and exact Gaussian-elimination `det`/`inv`/`solve`) over the built-in
rank-1/rank-2 arrays.

## Wiring

- **Package**: `linalg` is a built-in package registered in the
  `PackageRegistry` constructor; the exported names live in
  `PackageRegistry.LINALG_FUNCTIONS` (exposed as `linalgFunctionNames()`).
  Like `rontolisp`, it does not use `cl`, so inside `(in-package linalg)`
  standard names need `cl:` qualification. Adding a function = add the name
  there + a defun in `linalg.lisp` (+ per-operator doc pages, en and ja).
- **Driver**: `am.ik.rontolisp.eval.LinalgLibrary`, a simplified `JsonLibrary`
  mirror. Unlike JSON there is **no call-site rewriting**: every entry point is
  a plain defun (`&optional` desugars through `LambdaLists`), so
  `process(program)` only detects usage (any `linalg:`/`linalg::` qualified
  symbol anywhere, or a bare exported name while `(in-package linalg)` is in
  effect) and prepends `forms()`.
- **Interpreter (lazy load)**: no per-function dispatchers. `LispEvaluator.
  resolveFunction` falls back, on a missed lookup of a `linalg:`-qualified
  name, to evaluating `LinalgLibrary.forms()` into the global environment once
  and retrying. `#'linalg:name` works through the same path.
- **Compile path**: `RontoLispCli.compileToFile` and the web playground
  (`RontoPlayground.compileJvm/Wasm`) wrap the program as
  `LinalgLibrary.process(JsonLibrary.process(...))`. Compiler unit tests must
  call `LinalgLibrary.process` explicitly (see `compileAndRunLinalg` helpers).
- **Native image**: `linalg.lisp` is registered in
  `META-INF/native-image/.../resource-config.json` (typeReachable
  `LinalgLibrary`).

## Source constraints (linalg.lisp)

- Written in canonical package shape (external `linalg:name` defuns, internal
  `linalg::%la-*` helpers, bare `cl` names), so resolving it is a fixed-point
  no-op — pinned by `PackageResolverTest.linalgLibraryFormsAreAResolverFixedPoint`.
- `%la-bcast`'s broadcast lambdas capture the operator and the scalar operand,
  so its parameters use `%la-` names: the compiled backends resolve a captured
  name against a same-named user global first (`.todo/47`; the ci-spec
  `dynamic-function-selection` case defines a global `f` and broke `linalg:add`
  before the rename).
- Arithmetic is generic: integer inputs stay exact (ratios), so
  `det`/`inv`/`solve`/`linspace`/`mean` of integer inputs are deterministic
  across backends (pinned by the `linalg-package-cross-backend` ci-spec case).
- Flat iteration helper pair `%la-fref`/`%la-fset` addresses element k of a
  rank-2 array as `(row, col) = ((k - k mod cols) / cols, k mod cols)`;
  `cols = 0` marks rank 1.
- Not supported: `--no-gc` (arrays), runtime `eval` of linalg forms (the
  emitted eval runtime has no array ops), broadcasting between different
  non-scalar shapes (numpy-style row/column broadcast).

## Standard array functions added alongside

`array-dimensions` is the one new backend primitive (interpreter
`Environment.registerArrays`; JVM `_arrayDims` helper in
`JvmArrayRuntimeBuilder` reading the cols header slot; WASM inline emission in
`WasmArrayCompiler.compileDims` reading the dims buckets array). Everything
else is `LispMacroExpander` expansion over existing primitives: `vector`
(make-array + %aset), `svref` (aref; also a setf place sharing the `%aset`
case), `array-rank`/`array-dimension`/`array-total-size` (over
array-dimensions), and `coerce` (literal `'list`/`'vector`/`'string` only,
runtime dispatch on listp/stringp). The JVM helper gating
(`JvmLispCompiler.programUsesAnyArrayOp`) must list the derived names too,
because the scan runs before expansion. None are first-class function values
(matching `aref`/`make-array`).
