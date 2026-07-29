# Declarations as no-ops, eval-when, check-type/assert (Phase 3 unit 1)

Origin: the first unit of the ASDF Phase 3 split in
`.todo/054-asdf-support.md` (shipped 2026-07-05). Goal: real CL library sources
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
  hash-table, function -> `functionp`; `boolean` -> `(or (null v) (eq v t))`;
  `unsigned-byte` -> `(and (integerp v) (>= v 0))`; `vector`/`simple-vector`/
  `array`/`simple-array` -> `(or (stringp v) (%arrayp v))` (the rank is NOT
  checked -- a rank read would drag the gated array helpers into every
  typecase-using program); `sequence` adds `listp`; `t`/`otherwise` -> t;
  literal `nil` spec -> nil (the empty type). `functionp` is a real public
  builtin (Environment + Jvm/WasmFunctionpCompiler: JVM = Object[] with an
  Integer funcId in slot 0, WASM = `ref.test TYPE_CLOSURE`); `%arrayp` is a
  CL_INTERNALS-only predicate (JVM = `instanceof java.util.ArrayList`; WASM
  distinguishes an array cell from a hash table by testing the header car
  for the dims array vs the i31 count).
- Compound: `(or ...)`/`(and ...)`/`(not ...)` recurse; `(member items...)`
  -> `(member v '(items))` (eql, truthy tail); `(eql obj)` -> `(eql v 'obj)`;
  `(satisfies fn)` -> `(fn v)`; `(integer|float|rational|real|number [low
  [high]])` -> base predicate + bound checks, `*` = unbounded, `(n)` =
  exclusive bound.
- Type-specifier symbols match by their package-STRIPPED name
  (`plainTypeName`): standard type names are not all registered CL symbols,
  so inside a user package the resolver qualifies e.g. `unsigned-byte` to
  `pkg::unsigned-byte` (found by the split-sequence e2e, todo-061).
  Complementing that, `PackageRegistry.CL_TYPES` registers the common
  type-only names (float family, unsigned-byte, sequence, satisfies,
  otherwise, ...) so they resolve BARE in the first place -- required where
  the name reaches RUNTIME data, e.g. parse-number's `'double-float` compared
  by the runtime `coerce` dispatch (symbols compare by name, so a qualified
  spelling would never match).
- **The four float type names are ONE type, and `subtypep` says so on purpose**
  (`LispMacroExpander.canonicalSubtypeName` collapses `single-float`/
  `double-float`/`short-float`/`long-float` to `FLOAT`). rontolisp has exactly
  one float format -- `1.0s0`, `1.0f0`, `1.0d0` and `1.0l0` all read to the same
  value, `type-of` answers `FLOAT` for each and a float is `typep` of all four
  names -- and CLHS explicitly permits an implementation to have as few as one
  distinct float format, in which case the four names denote the same type and
  `(subtypep 'single-float 'double-float)` is genuinely `T`. So this is NOT the
  "dishonest lattice" `.todo/200` suspected: postmodern's
  `json-encoder.lisp` `eval-when` probes exactly these pairs and, answered this
  way, takes its `:cl-json-only-one-float-type` branch, which is the correct one
  here. **Re-evaluation trigger**: this stays right only while there is one float
  representation. If a distinct single-float ever lands, `canonicalSubtypeName`
  must stop collapsing the names and the lattice must gain the real
  `single-float <= double-float <= long-float` edges in the SAME pass, or every
  library that probes the float lattice silently takes the wrong branch. Pinned
  by the `postmodern-language-incidentals` ci-spec case, which asserts the pair
  `(T T)`. Separately, `subtypep` returns ONE value here (CL returns a second
  "certain?" value); every known consumer uses only the primary.
- `deftype` is a parsed no-op returning nil (the name is NOT registered;
  using it in a later type test errors) -- enough for the library shape
  where the type only appears in no-op declaim/declare declarations.
- The value form may be evaluated several times, so callers bind a temp
  first (`__check-type` / typecase's `__typecase`).

## Top-level flattening (flattenTopLevel)

`LispMacroExpander.flattenTopLevel(program)` recursively splices top-level
`(progn ...)` and `(eval-when (sits) ...)` into top-level forms. Called at:

1. `UserMacroExpander.expand` entry (CLI path) -- so the
   `(eval-when (:compile-toplevel ...) (defmacro ...))` idiom registers the
   macro at compile time,
2. `JvmLispCompiler.compile` / `WasmLispCompiler.compile` /
   `NoGcWasmCompiler.compile`, right before/at the top-level preprocessing
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
compileCons cases; `NoGcWasmCompiler.expandMacro`; `FreeVarAnalyzer` BOTH
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
