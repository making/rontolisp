# `symbol-macrolet` — one shared shadow-aware substitution, all four backends

`LispMacroExpander.expandSymbolMacrolet` lowers `(symbol-macrolet ((name expansion)...) body...)`
into a `progn` with every FREE reference replaced -- one walk (`substituteSymbolMacros`) shared by
the interpreter, the JVM and both WASM backends. **Do NOT add per-backend symbol-macro handling.**
Consumers: trivia `match`, dbi `driver.lisp`, mito `core/type.lisp`.

- **Shadow-aware**, unlike the lite `substituteSymbols` behind `with-slots` (do not merge that
  behavior in). Every binder removes its names for its scope.
- **Data positions kept verbatim**: quote, declares, `#'name`, call heads, `case`/`typecase` keys,
  `go` tags, `block` names, `tagbody` atom tags, type tails, `eval-when` situations,
  `defmacro`/`macrolet` bodies, and the `defclass`/`defstruct`/`defgeneric`/`deftype`/
  `define-condition`/`defsetf` family. `typecase` is walked structurally.
- `(setq name v)` whose substituted target is a cons becomes `setf`. `loop` and `prog`/`prog*` are
  expanded one step first (`expandLoop`/`expandProg`). Declarations directly in the body are
  DROPPED.
- **User macros must expand BEFORE substitution**, and the walk has no macro table: every path runs
  `UserMacroExpander.expandAll` first. The interpreter's arm (`LispEvaluator.expandSymbolMacrolet`,
  from `rareOperatorExpansion`, out of `evalCons`'s hot table, `LispEvaluatorHotMethodSizeTest`) does
  it once per call site, in the memo of `expandUserMacro`, in the package the code was read in.
  A macrolet in the body so expands before the walk (a local macro's `'s` used to stay unbound).
- Wiring: evaluator case, `Jvm/WasmExprCompiler.compileCons`, `FreeVarAnalyzer`,
  `rewriteLocalCalls`, `expandBuiltinMacro`.
- Lite: `defmethod` `&optional`/`&key` defaults are unwalked; a special-variable name is substituted
  anyway.

## `macroexpand` through `&environment`

A macro reads a symbol macro with `(macroexpand 'name env)`; the answer is the object the binding
holds, because the idiom writes into it (3bz `state-machine`, `deflate.lisp:67`: `.current-state`
is never referenced, only stored into by one macro and read by a later one).

- `expandAll`'s scope: `LispEvaluator.symbolMacroScope()`, a thread-local table of what is in
  scope at the form it walks (the global table when it has not narrowed or extended it). The
  `SYMBOL_MACROLET` case extends it with the raw binding objects, for the binding forms too (SBCL
  expands them at the reference, `((s 2) (u (mx s)))` reads 2). `expandBinderOfSymbolMacroName`
  narrows it only for a form binding a name in it, so a program with no symbol macro takes the
  ordinary walk: same binders and scoping as `substituteSymbolMacros`, which rewrites instead.
- `&environment` is not a `let` around the body any more: `UserMacro.environment()`, bound by
  `expandMacroCall` (`environmentAt`). Nil when no symbol macro exists anywhere; otherwise a
  `LispJavaObject` over `SymbolMacroEnvironment`, which every other reader ignores. A macro body
  runs with no walk scope, the null lexical environment; `macroexpand`/`macro-function`'s expander
  given an environment hand it on to the macro they call.
- The null environment holds the globals only (SBCL's `(macroexpand 's)` under a local `s` is `S`).
  An interpreted call reached while running has no walk: `environmentAt(site)` drops the globals
  its `Environment` binds lexically.
- A macro with `&environment` under a walk scope skips both per-call-site memos (`expandsPerScope`):
  one cons a macro spliced twice is two scopes.
- `(macroexpand 'sym env)` is not folded when `env` is computed: `expandAll` walks the body of a
  helper a macro calls before the macro-time evaluator sees it. It stays a call, and the prelude
  defun answers the symbol at run time.
- Lite: `macrolet` macros stay a dynamic table, visible through a nil environment too; a
  `with-slots`/`with-accessors` name is shadowed by nothing and not in the table (they run
  `substituteSymbols`); `get-setf-expansion` ignores the environment; `JavaDeclarations`' pre-pass
  over a `java:` program expands macros with a nil environment.

## `define-symbol-macro` (global)

- Interpreter: `LispEvaluator.globalSymbolMacros` (written by `DEFINE-SYMBOL-MACRO` in
  `evalConsRareOperator`); `evalSymbolRef` consults it only after `env.lookupOrNull` is empty,
  `evalSetq` rewrites to `(setf expansion value)`. `symbol-value` does not see the name.
- Compile paths: `UserMacroExpander.harvestSymbolMacros` runs per top-level form after `expandAll`,
  registers and REMOVES definitions reachable through `progn`/`eval-when`, recording the activation
  index; `substituteGlobalSymbolMacros` then substitutes. Activated by `usesDefineSymbolMacro`.
- A non-top-level definition is refused with `must be a top-level form`
  (`nestedDefineSymbolMacro`); the interpreter just registers it.
- **Trap**: `expandAll`'s `SETF` case must expand a user setf place FIRST and walk the expansion, or
  a COMPILER MACRO on the accessor renames the place and the accessor-keyed
  `define-setf-expander` misses it (cffi's `mem-ref`). Consumer: `cffi:defcvar` (`.kb/cffi.md`).

## Tests
`LispEvaluatorTest#evalSymbolMacrolet*`,
`#evalDefineSymbolMacroReadsAndWritesThroughTheExpansion`,
`JvmLispCompilerTest#compileAndRunSymbolMacrolet*` / `#compileAndRunDefineSymbolMacro`
(+ `…EmittedByUserMacro`), `WasmLispCompilerIntegrationTest#symbolMacroletForms` /
`#defineSymbolMacroForms`, `CffiSystemTest#defcvarReadsAndWritesARealCGlobal`, ci-spec
`symbol-macrolet-substitution-and-setf`, `define-symbol-macro-read-and-write`,
`macroexpand-environment-symbol-macros`.
