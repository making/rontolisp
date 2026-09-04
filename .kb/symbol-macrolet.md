# `symbol-macrolet` — one shared shadow-aware substitution, all four backends

`LispMacroExpander.expandSymbolMacrolet` lowers `(symbol-macrolet ((name expansion)...)
body...)` into a `progn` with every FREE reference replaced by its expansion -- one walk
(`substituteSymbolMacros`) shared by the interpreter, the JVM and both WASM backends. **Do
NOT add per-backend symbol-macro handling.** Consumers: trivia `match`, dbi `driver.lisp`,
mito `core/type.lisp`.

- **Shadow-aware**, unlike the lite `substituteSymbols` behind `with-slots` (whose
  inner-binding-still-substitutes behavior must not be merged in). Every binder removes its
  names for its scope, including `destructuring-bind` patterns (conservative) and
  `dolist`/`dotimes` result forms.
- **Data positions kept verbatim**: quote, declares, `#'name`, call heads,
  `case`/`typecase` keys, `go` tags, `block` names, `tagbody` atom tags, type tails,
  `eval-when` situations, `defmacro`/`macrolet` bodies, and the `defclass`/`defstruct`/
  `defgeneric`/`deftype`/`define-condition`/`defsetf` family. `typecase` is walked
  structurally (its expansion needs the class registry, as in `FreeVarAnalyzer`).
- `(setq name v)` whose substituted target is a cons becomes `setf`; a textual `setf` needs
  nothing. `loop` and `prog`/`prog*` are expanded one step first (`expandLoop`/`expandProg`).
- A substituted expansion is re-walked with the just-replaced name removed, so sibling chains
  terminate and a self-referential expansion substitutes once (CL errors; lite).
- Declarations directly in the body are DROPPED (trivia names macro symbols in them).
- **User macros must expand BEFORE substitution.** `expandSymbolMacrolet(cons, hook)` takes
  `LispMacroExpander.UserMacroHook`: interpreter =
  `LispEvaluator.symbolMacroUserMacroHook`, wired in `evalConsRareOperator` (the hot half
  sits near HugeMethodLimit, `LispEvaluatorHotMethodSizeTest`); compile path = null, since
  `UserMacroExpander.expandAll` has a SYMBOL-MACROLET pattern-keeping case.
- Wiring: evaluator case, `Jvm/WasmExprCompiler.compileCons`, `FreeVarAnalyzer`,
  `rewriteLocalCalls`, `expandBuiltinMacro`.
- Lite: the interpreter re-substitutes per evaluation; `defmethod` `&optional`/`&key`
  defaults are unwalked; a special-variable name is substituted anyway.

## `define-symbol-macro` (global)

- **Interpreter**: `LispEvaluator.globalSymbolMacros`, written by `DEFINE-SYMBOL-MACRO` in
  `evalConsRareOperator`; `evalSymbolRef` consults it only after `env.lookupOrNull` is empty
  (map emptiness-gated), and `evalSetq` rewrites to `(setf expansion value)`.
  `symbol-value` does not see the name; a `let` of it binds an ordinary shadowing lexical.
- **Compile paths**: `UserMacroExpander.harvestSymbolMacros` runs per top-level form after
  `expandAll`, registers definitions reachable through `progn`/`eval-when` nesting (the
  `cffi:defcvar` shape) and REMOVES them, recording the emitted-form index each becomes
  active at; one final `substituteGlobalSymbolMacros` (→
  `LispMacroExpander.substituteGlobalSymbolMacros`) substitutes each form through the
  definitions in effect at it. Activated by `usesDefineSymbolMacro`.
- A non-top-level definition is refused with `must be a top-level form`
  (`nestedDefineSymbolMacro`, which skips `quote`/`defmacro`/`macrolet`); the interpreter
  just registers it.
- **Trap**: `expandAll`'s `SETF` case must expand a user setf place FIRST and walk the
  expansion, or a COMPILER MACRO on the accessor renames the place and the accessor-keyed
  `define-setf-expander` misses it (cffi's `mem-ref`). Consumer: `cffi:defcvar`
  (`.kb/cffi.md`).

## Tests
`LispEvaluatorTest#evalSymbolMacrolet*`,
`#evalDefineSymbolMacroReadsAndWritesThroughTheExpansion`,
`JvmLispCompilerTest#compileAndRunSymbolMacrolet*` / `#compileAndRunDefineSymbolMacro`
(+ `…EmittedByUserMacro`), `WasmLispCompilerIntegrationTest#symbolMacroletForms` /
`#defineSymbolMacroForms`, `CffiSystemTest#defcvarReadsAndWritesARealCGlobal`, ci-spec
`symbol-macrolet-substitution-and-setf` and `define-symbol-macro-read-and-write`.
