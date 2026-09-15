# Adding a primitive: built-in function, macro, special form

The per-surface checklists `CLAUDE.md` refers to. Each step is load-bearing; the traps
in the built-in function list (step 3 and step 4) are the ones that compile clean and
fail silently at the call site.

## Adding a Built-in Function

1. `LispNames` constant + `PackageRegistry.CL_SYMBOLS` entry (else it is misclassified as a
   user symbol).
2. `Environment.createGlobal()`: `env.define("name", new LispFunction(...))` -> `LispEvaluatorTest`
3. `Jvm<Name>Compiler` + a case in `JvmExprCompiler.compileCons()` -> `JvmLispCompilerTest`.
   **A `rontolisp:`-package name does NOT go through that switch** -- it never reaches a
   `cl:` symbol there, since `compileConsLocated` dispatches every `rontolisp:` member
   through a SEPARATE qualified-name if-chain first (keyed on
   `PackageRegistry.splitQualified(sym.name())`; every existing `rontolisp:` primitive,
   e.g. `version`/`tcp-connect`/`bfloat16-bits`, is a case there). Adding a `rontolisp:`
   name's case to the `compileCons()` switch instead compiles clean and then silently
   falls through to "undefined function" at the call site -- it never gets a chance to
   match, since the qualified if-chain already returned.
4. `Wasm<Name>Compiler` + a case in `WasmExprCompiler.compileCons()` -> `WasmLispCompilerIntegrationTest`
   (`WasmEmitHelper.castI31GetS()` to unbox, `ref.i31` to re-box). The same split as
   step 3 applies here: `WasmExprCompiler.compileConsLocated` has its own
   `PackageRegistry.splitQualified`-keyed if-chain for `rontolisp:` members, separate
   from the `cl:`-symbol switch.
5. `BuiltinFunctionWrappers.WRAPPER_DEFS` entry so it works as a first-class value.
6. A case in `src/test/resources/ci-spec.yaml` if it deserves end-to-end coverage.
7. Docs: a per-operator page under `reference/{functions,macros,special-forms}/` (H1 = name,
   signature, one runnable ```lisp example with a `; => value`), a `_catalog.yaml` entry, and
   a row in that package's function page (`reference/functions/<package>.md` -- `cl.md` for
   the standard package; see the category's `index_page` in `_catalog.yaml`).
8. If its trailing arguments are a BODY, an `am.ik.rontolisp.format.IndentRules` entry --
   without one `rontolisp format` lays the body out as a function call (`.kb/formatter.md`).

## Adding a Prelude Function (pure Lisp over existing primitives)

When the operator is expressible in Lisp over primitives every backend already has
(`decode-float`, and `.todo/037` Slice C's `logcount`/`rationalize`/`integer-decode-float`),
a `LispPreludeLibrary.SOURCES` defun replaces steps 2-5 above entirely: one implementation
runs on the interpreter, the JVM and WASM-GC, is first-class for free (it IS a defun), and
`values` in its tail gives multiple values through the syntactic tier
(`.kb/multiple-values.md`) on every backend that carries them. The remaining checklist:
`LispNames` constant + `PackageRegistry.CL_FUNCTIONS` entry (a name that gains an
implementation moves OUT of `CL_EXPORTED_ONLY`, keeping the 978 externals pinned by
`PackageRegistryTest`), a `ci-spec.yaml` case, and the step-7 docs. No `Environment` entry,
no per-backend compiler, no wrapper entry.
- The prelude defun goes through the same compile pipeline, so a call it contains that a
  backend refuses (e.g. `rational` on `--no-gc`) refuses the caller too -- by reachability,
  only when called. What the scalar backend CAN share gets a `LispMacroExpander`
  lowering + a `NoGcWasmCompiler.expandMacro` case instead (Slice A pattern); what it
  cannot (multiple values, ratios) is refused there by omission or by an explicit case
  beside `RATIONAL`, like `rational` itself.
- A shared lowering must be DETERMINISTIC -- fixed temporary names, never an
  `MV_COUNTER` gensym: `--no-gc`'s `inferTypes` fixpoint re-expands every reached call
  on every pass, so a fresh name per expansion registers a new local per pass and the
  `changed` flag never settles (an infinite compile). See `.kb/no-gc-scalar-wasm.md`.

## Adding a Macro

Macros expand into existing primitives at the AST level; `LispMacroExpander` is shared by the
evaluator and both compilers, so no per-compiler class is needed.

1. `LispMacroExpander.expand<Name>(LispCons)`, plus `LispNames` / `PackageRegistry.CL_SYMBOLS`.
2. `LispEvaluator.evalCons()` case -> `eval(LispMacroExpander.expand<Name>(cons), env)`.
3. `Jvm`/`WasmExprCompiler` case -> `compileExpr(LispMacroExpander.expand<Name>(cons), ...)`.
4. To pass it to `map`/`reduce`/`funcall`: register as a `LispFunction` in `Environment` AND
   add a `BuiltinFunctionWrappers` entry. Both -- omitting `Environment` causes
   `Undefined symbol` in interpreter / native-image mode.

## Adding a Special Form

`LispEvaluator.evalCons()` case (arguments arrive unevaluated), plus
`Jvm/Wasm<Form>Compiler` wired into `Jvm/WasmExprCompiler.compileCons()`.

