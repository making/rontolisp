# `defmacro` (user macros) + read-time backquote — NO backend codegen involved

Backquote (`` ` ``/`,`/`,@`; `Token.Backquote`/`Unquote`/`UnquoteSplicing`, `,` and `` ` `` are symbol-terminating chars, digit-grouping `1,000` still lexes inside `readNumber`) is expanded BY THE READER (`LispReader.readBackquote`/`readTemplateElement`) into plain `list`/`append`/`quote` forms, so all backends get it for free; nested backquote is a read error.

`defmacro` (`CL_SPECIAL_FORMS`; lambda list = required params + one trailing `&rest`/`&body`, no `&optional`/`&key`; cannot redefine a cl symbol; no function value) is handled per path:

- The **interpreter** keeps a macro table on `LispEvaluator` (`userMacros`, `expandUserMacro` binds the UNevaluated arg forms and evals the body; checked in `evalCons` after the built-in switch, so it also works in REPL/`load`/runtime `eval`).
- The **compile path** runs `eval.UserMacroExpander.expand` in `RontoLispCli.compileToFile` (after `LoadInliner`, before the compilers — same pattern), which evals `defmacro` forms into a macro-time `LispEvaluator`, registers top-level `defun`s (registration only, so macro bodies can call helpers), fully expands every call site with a structure-aware walker (skips `quote`, `let`/`do` binding names, `lambda`/`defun` params, `case`-family keys, `dolist`/`dotimes` vars) and drops the definitions — the compilers never see a macro form, so there is NO `Jvm/Wasm` macro compiler and nothing to keep in sync. Anything compiling programs without the CLI (corpus tests) must apply the pass itself.

Consequences: the runtime `_eval`/`read` of compiled output knows neither `defmacro` nor `` ` ``; macros must be defined before use.

Tests: `LispReaderTest`/`LispLexerTest` (backquote), `LispEvaluatorTest` (defmacro), `UserMacroExpanderTest`, `JvmLispCompilerTest#compileAndRunUserMacroAfterExpansionPass`, ci-spec `defmacro-user-macros`. Follow-ups in `.todo/44`.
