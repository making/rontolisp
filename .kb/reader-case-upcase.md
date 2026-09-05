# Reader case: uppercase-canonical (upcase, no fold)

The reader upcases unescaped symbol characters like CL's `:upcase` readtable case, and the
upcased name IS the canonical spelling -- nothing downstream (resolver, splice scanners,
evaluator, all backends) folds back to lowercase, and there is no case-preserving island for
rontolisp's own lowercase-authored sources. `foo`/`FOO` -> `FOO`, keywords upper, built-in
package members too (`rl:fetch` -> `RL:FETCH`).

## Reader and runtime
- Escapes are per-character in `LispLexer.readSymbol` (the ONLY place escape info exists): `\c`
  or anything inside `|...|` is verbatim, so `|car|` is distinct from `CAR`. The number-fallback
  token (`numberFallbackSymbol`, e.g. `1+`) upcases like any symbol.
- `intern`/`make-symbol`/`find-symbol` are VERBATIM on every backend ([[symbol-runtime-api]]);
  `intern` still homes a bare name via `internSpelling`.
- `read`/`read-from-string` upcase on ALL FOUR backends: `Features.INTERPRETER`, the JVM's
  `_classify` (`toUpperCase(Locale.ROOT)`), and WASM upcasing token bytes in place before
  `_intern`. Non-ASCII code chars on WASM remain a limitation.
- `symbol-name`/print report the stored upcased spelling; `LispSymbol.displayName` strips a
  keyword/gensym package marker. Java-side name synthesis goes through
  `LispMacroExpander.affixFor`, which case-matches its base. Output side: [[pretty-printer]].

## Case-tolerance seams that REMAIN (keep them)
- `LispEvaluator.instanceSlotCell` uses `equalsIgnoreCase` on slot base names: Java callers spell
  built-in condition slots lowercase (`conditionSlotValue` passes `"format-control"`) against an
  upcase-read `FORMAT-CONTROL`. Compiled twin: `LispMacroExpander.expandConditionSlotReader`.
- `PackageResolver.resolveUnqualified`/`resolveQualified` one-shot lowercase retry bridges an
  upcased reference to a `wit-import` package's lower-kebab member; `resolveUnqualified` ALSO
  retries UPPERCASE for a `wit-import` directive's own synthesized quoted name
  (`WitImportDirective` builds `new LispSymbol(member)`, bypassing the reader). **Trap it fixes:**
  with a HAND-WRITTEN `defpackage` the `:export` clause upcases, so the binding resolved to
  internal `GL::create-shader` while call sites resolved to external `GL:CREATE-SHADER` --
  compiled under one name, called under another, a WASM stub trapping at `unreachable` only when
  run. Pinned by
  `PackageResolverTest#bareLowerKebabWitImportNameResolvesAgainstAHandWrittenUppercaseExport`
  and `WitImportDirectiveTest#lowersAFreestandingInterfaceWithTheDefaultCamelCaseFields`.
- HTTP keys are UPPERCASE on all four backends: `compiler/FetchResponseShape`
  (`:STATUS`/`:HEADERS`/`:BODY`) and `compiler/ClackEnv` -- a host-ABI decision
  ([[fetch-http]], [[http-server]]).

## Everything else matches EXACTLY (no `equalsIgnoreCase`)
`LispNames.keywordMatches`/`foldKeyword` are deleted; every keyword matcher is `.equals`,
including `LispMacroExpander.requireKeywords`.
- WIT type designators uppercase internally (`WasmExportCompiler.T_INT` = `":INT"` .. `T_VOID`,
  `KNOWN_TYPES`); `WitExport`/`WitImportDirective` synthesize the upcased spelling.
- CLOS qualifiers uppercase (`plainTypeName`, `expandDefmethod`, `applicableMethods(...,
  ":BEFORE"/":AFTER"/":AROUND", ...)`); initargs exact (`buildTypedConstruct`,
  `slot.initargKeyword()` = `":" + baseName`). `AsdfSystems` switches on uppercased labels.
- Every hard-coded canonical literal is upcased (`:TEST`, `:KEY`, `:IF-EXISTS`, `:UTF-8`,
  `:FORMAT-CONTROL`, ...).
- The `--component` canonical ABI is UPCASED-ONLY both directions:
  `WasmComponentImportCompiler` LOWER compares the upcased tag only, and `wit.lisp`'s
  `%wit-result` accepts only the upcased `:OK` head (same in `http.lisp`, `sockets.lisp`,
  `usocket.lisp`). Pinned by `WasmComponentImportCompilerTest`, `ServeMethodCaseComponentE2eTest`.

## Host-facing names derive lowercased
wasm-export/import default names, `:as` aliases, `:param-names` and wit-export `:world`
designators `toLowerCase` at the single emit point (`componentValType` / `WitEmitter` /
`WitExportDirective.worldName`); string spellings verbatim. An ASDF SYMBOL system designator
downcases like `coerce-name` (`AsdfSystems.symbolName`).

## Tests
`LispReaderTest`/`LispLexerTest`, `LispEvaluatorTest`, `JvmLispCompilerTest` /
`WasmLispCompilerIntegrationTest`, ci-spec `read-from-string-upcase` and `symbol-runtime-api`,
`AssocUtilsUpcaseE2eTest`, `WitScaffolderTest.theScaffoldedProgramCompilesUnchanged`. Docs:
`doc/{en,ja}/guides/reader-case.md`.
