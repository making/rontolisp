# Declarations as no-ops, eval-when, check-type/assert (Phase 3 unit 1)

Origin: the first unit of the ASDF Phase 3 split in
`.todo/54-asdf-support.md` (shipped 2026-07-05). Goal: real CL library sources
parse and load -- nearly every library body contains `declare`/`declaim`, and
macro-exporting libraries wrap `defmacro` in `eval-when`.

## What ships

All seven operators are `LispMacroExpander` expansions (no per-backend
codegen), classified in `PackageRegistry.CL_MACROS` (precedent: `error` is a
"macro" here too) with `expandBuiltinMacro` cases so `macroexpand-1` works:

- `declare`, `declaim`, `proclaim` -> `nil`. Arguments are never evaluated or
  validated (`proclaim` deviates from CL, where it is a function with an
  evaluated argument). A `declare` in a body is harmless because bodies
  discard non-final values; a `declare` as the LAST body form returns nil,
  which is invalid CL code anyway.
- `the` -> its value form (identity).
- `eval-when` -> `progn` of the body (every situation = "evaluate now").
- `check-type` -> `(let ((__check-type place)) (if <type-test> nil (error
  "The value of <place> is ~s, which is not of type <spec>." __check-type)))`.
  The place and spec are printed into the message at expansion time; only the
  value is a runtime `~s` argument. An optional third string replaces the
  "of type ..." part.
- `assert` -> `(if test nil (error ...))`; with the full form the datum+args
  become the error call and the places list is dropped (no restart system).

## makeTypeTest (shared type-specifier tests)

`LispMacroExpander.makeTypeTest(value, spec)` builds a truthy test form; the
old `makeTypecaseTest` now delegates to it, so `typecase`/`etypecase` clause
heads accept the same specs as `check-type`:

- Atomic map (`atomicTypePredicate`): integer/fixnum/bignum -> `integerp`,
  float* -> `floatp`, number/real -> `numberp`, rational/ratio ->
  `rationalp`, string, symbol, keyword, cons, list, null, atom, character,
  hash-table; `boolean` -> `(or (null v) (eq v t))`; `t`/`otherwise` -> t;
  literal `nil` spec -> nil (the empty type).
- Compound: `(or ...)`/`(and ...)`/`(not ...)` recurse; `(member items...)`
  -> `(member v '(items))` (eql, truthy tail); `(eql obj)` -> `(eql v 'obj)`;
  `(satisfies fn)` -> `(fn v)`; `(integer|float|rational|real|number [low
  [high]])` -> base predicate + bound checks, `*` = unbounded, `(n)` =
  exclusive bound.
- The value form may be evaluated several times, so callers bind a temp
  first (`__check-type` / typecase's `__typecase`).

## Top-level flattening (flattenTopLevel)

`LispMacroExpander.flattenTopLevel(program)` recursively splices top-level
`(progn ...)` and `(eval-when (sits) ...)` into top-level forms. Called at:

1. `UserMacroExpander.expand` entry (CLI path) -- so the
   `(eval-when (:compile-toplevel ...) (defmacro ...))` idiom registers the
   macro at compile time,
2. `JvmLispCompiler.compile` / `WasmLispCompiler.compile` /
   `ScalarWasmCompiler.compile`, right before/at the top-level preprocessing
   (`expandTopLevelDefstructs`) -- so Pass 1 collects nested defuns in direct
   compiler invocations (compiler unit tests bypass the CLI).

The interpreter does NOT flatten: it evaluates `eval-when` natively as
`progn`, which reaches the same result at runtime. A malformed
`(eval-when)` without a situation list is left unspliced so the expander's
validation error surfaces.

Known gap: `LoadInliner` runs BEFORE UserMacroExpander, so a top-level
`(load ...)`/`(require ...)`/`(asdf:load-system ...)` wrapped in `eval-when`
is NOT inlined on the compile path (it falls through to the runtime `load`).
Unwrapped directives are unaffected.

## Wiring points (the usual macro checklist)

`LispNames` constants; `LispEvaluator.evalCons` cases; `Jvm/WasmExprCompiler`
compileCons cases; `ScalarWasmCompiler.expandMacro`; `FreeVarAnalyzer` BOTH
methods (explicit cases that expand first -- the default walk would misread
the type symbol in `(the integer x)` as a free variable reference and collect
declaration specifiers); `expandBuiltinMacro`.

## Pinning tests

- `LispEvaluatorTest`: `evalDeclareIsANoOp`, `evalDeclaimAndProclaimAreNoOps`,
  `evalTheReturnsTheValue`, `evalEvalWhenActsAsProgn`,
  `evalEvalWhenWrappedDefmacro`, `evalCheckType`,
  `evalCheckTypeSatisfiesAndMember`, `evalAssert`,
  `evalTypecaseCompoundSpecifiers`.
- `JvmLispCompilerTest`: `compileAndRunDeclarations`, `compileAndRunThe`,
  `compileAndRunEvalWhen`,
  `compileAndRunEvalWhenWrappedDefmacroThroughUserMacroExpander`,
  `compileAndRunCheckType`, `compileAndRunAssert`.
- `WasmLispCompilerIntegrationTest`: `declarationsTheAndEvalWhen`,
  `checkTypeAndAssertForms`.
- ci-spec: `declarations-eval-when-check-type-assert`; the
  `rontolisp-package-introspection` case's `list-macros` line now includes
  the seven names (same expectation updated in all three backend test
  classes).
