# 29 - `loop` macro follow-ups (extend the supported clause subset)

The extended subset is done on all four backends — numeric/list/`=` stepping,
`with` (parallel + destructuring), `for ... in/on/across/being` (hash tables),
the seven accumulators with `into`, `while`/`until`/`repeat`/`do`/`return`/
`initially`/`finally`, `when`/`if`/`unless` with `else`/`end`, parallel `and`,
anaphoric `it`, `thereis`/`always`/`never`, `loop-finish`, and `named` +
`return-from` (wraps the expansion in `(block name ...)`, `.kb/do-return-block.md`).
One shared expansion: `LispMacroExpander.LoopExpander` (wired in `LispEvaluator`,
`Jvm/WasmExprCompiler`, `NoGcWasmCompiler.expandMacro`, `FreeVarAnalyzer`),
mechanics in `.kb/loop-iteration-heads.md`, behavior in
`doc/{en,ja}/reference/macros/loop.md`. Tests: `LispEvaluatorTest#evalLoop*`,
`JvmLispCompilerTest#compileAndRunLoop*`,
`WasmLispCompilerIntegrationTest#loopExtendedClausesCompileAndRun`, ci-spec
`loop-macro-extended-clauses`.

## Remaining

- **`being` over a PACKAGE**: `symbols`/`present-symbols`/`external-symbols` parse,
  but are a lite no-op: rontolisp has no runtime intern table
  (`.kb/symbol-runtime-api.md`), so the package form is evaluated once for effect
  and the EMPTY sequence is iterated — `VAR` binds to nil and the body never runs
  (`LispMacroExpander.parseForBeing`). Enough for cl-who's hyperdoc table; a real
  enumeration needs the intern table first — its go/no-go is `.todo/156` Phase 5 (A1).
