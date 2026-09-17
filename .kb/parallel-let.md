# `let` is PARALLEL on the compile path (`ParallelLetStaging`), and `(+)`/`(*)` are identities

## Parallel `let`

**Invariant: every init form of a `let` is evaluated before any variable is bound, on all
four backends** -- in `(let ((a b) (b a)) ...)` the second init reads the OUTER `a`.

- `Jvm/WasmLetCompiler` bind ONE variable at a time (compile the init, store, register the
  name): that is what lets each binding pick its representation -- raw long / double slot,
  boxed cell, dynamic binding -- the moment its init is known. The order is observable
  only when a LATER init refers to an EARLIER variable's name.
- `compiler/ParallelLetStaging.stage` rewrites exactly that `let`, at the ENTRY of both
  let compilers (beside `LetBoundDesignators.propagate`), into
  `(let ((%let-init-0 i0) ...) (let ((a %let-init-0) ...) body...))`. A literal init binds
  directly. Any other `let` comes back as the SAME object: emitted bytes unchanged, typed
  slots kept.
- **Why at the let compilers and not an AST pre-pass**: `do`, `multiple-value-bind` and
  every other macro expanding to a `let` expand inside the expression compilers, after any
  pre-pass ran. The entry is the one place all of them reach.
- Hazard test: a symbol-scan prefilter, then `FreeVarAnalyzer.findFreeVars` with the name
  as `enclosingLexicals` (Lisp-2: a bare symbol is a variable whatever function, global or
  special shares its spelling), so `(let ((list (f)) (x (list 1))) ...)` is NOT staged and
  `(let ((*x* 1) (y *x*)) ...)` is.
- What it was before (found 2026-09-17 through the Scheme corpus): JVM answered `(2 2)`
  for the swap everywhere, wasm inside a `defun`, and a closure in the later init was a
  JVM compile error ("closure over X whose binding left it unboxed"). The interpreter and
  `--no-gc` (`NoGcWasmCompiler.compileLet` evaluates all inits first) were always right.

## Arithmetic with no arguments

`(+)` is 0 and `(*)` is 1 (CLHS 12.2). `Jvm/WasmArithCompiler` fold "first argument, then
the rest" and ask `compiler/ArithmeticIdentities.of` when there is no first; any other
operator with no argument is a compile error naming it. Was an
`IndexOutOfBoundsException` on both. `--no-gc` passes the identity to `compileVariadic`.

## Tests

`JvmLispCompilerTest.compileAndRunLetBindsInParallel` / `.compileAndRunArithmeticWithNoArguments`,
`WasmLispCompilerIntegrationTest.letBindsInParallel` / `.arithmeticWithNoArguments`, ci-spec
`let-binds-in-parallel`, `arithmetic-identities-with-no-arguments`; scheme-spec
`internal-defines-are-letrec-star` (the swap through the Scheme front end).
