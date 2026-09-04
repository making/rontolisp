# Reader case: uppercase-canonical (upcase, no fold)

The reader upcases unescaped symbol characters like CL's `:upcase` readtable case, and the
upcased name IS the canonical spelling -- there is no fold back to lowercase. `foo`/`FOO` ->
`FOO`; `defun` -> `DEFUN`; `t`/`nil` -> `T`/`NIL`; keywords are upper (`:FOO`); built-in
package members too (`rl:fetch` -> `RL:FETCH`, `ql:quickload` -> `QL:QUICKLOAD`). Nothing
downstream (resolver, splice scanners, evaluator, all backends) folds a name to lowercase, and
there is no case-preserving island for rontolisp's own lowercase-authored sources -- spliced
libraries, prelude, shims and `.asd` data all read upcased, and the Java-side matchers (splice
scanners, pruner, wrapper defs) match upcased names.

## Escapes

Per-character in `LispLexer.readSymbol` (the ONLY place escape info exists): an escaped
character (`\c`, or any char inside `|...|`) is kept verbatim and NOT upcased, so `|car|` is
the distinct symbol `car`, not `CAR`. The number-fallback token (`numberFallbackSymbol`, e.g.
`1+`) upcases the whole run like any other symbol.

## Runtime API

- `intern`/`make-symbol`/`find-symbol` are VERBATIM: `(intern "TIME")` names `TIME`,
  `(intern "time")` a distinct symbol, `(find-symbol "car")` is `NIL`, `(make-symbol "X")`
  twice is `eq` (no intern table; `.kb/symbol-runtime-api.md`). `LispEvaluator` passes
  `str.value()` straight through (`intern` still homes a bare name into the resolver's current
  package via `internSpelling`). The compiled backends' `intern` is verbatim too.
- `read`/`read-from-string` upcase on ALL FOUR backends: `(read-from-string "foo")` is `FOO`.
  Interpreter reads with `Features.INTERPRETER`; the JVM's `_classify` does
  `token.toUpperCase(Locale.ROOT)`; the WASM read runtime upcases the token bytes in place
  before `_intern` (length-preserving, so intern/nil/t path is unchanged). Non-ASCII code
  chars on WASM remain a limitation.
- `symbol-name`/print report the stored (upcased) spelling: `(symbol-name 'car)` = `"CAR"`.
  `symbol-name` still strips the package marker of a keyword/gensym
  (`LispSymbol.displayName`, shared with `princ`/`~A`/`string`; `prin1`/`print` keep the
  stored spelling). Java-side name synthesis from a user symbol goes through
  `LispMacroExpander.affixFor` (defstruct `MAKE-PT`/`PT-P`, class `%MAKE-`+base), which
  case-matches its base. `*print-case*` conversion on output: `.kb/pretty-printer.md`.

## Case-tolerance seams that REMAIN (not the fold -- keep them)

- `LispEvaluator.instanceSlotCell` compares slot base names with `equalsIgnoreCase`: a
  Java-side caller spells a built-in condition slot lowercase (`conditionSlotValue` passes
  `"format-control"`) while an upcase-read condition registers `FORMAT-CONTROL` (same
  reconciliation as `LispMacroExpander.expandConditionSlotReader` for the compiled backends).
  This is why `simple-condition-format-control` returns the control string, not `NIL`.
- `PackageResolver.resolveUnqualified`/`resolveQualified` one-shot lowercase retry: bridges an
  upcased reference to a `wit-import` package's lower-kebab member (`GL:CREATE-SHADER` ->
  wit-imported `gl:create-shader`). `resolveUnqualified` ALSO retries the mirror direction
  (one-shot UPPERCASE) for a `wit-import` directive's OWN synthesized quoted name:
  `WitImportDirective` builds it as `new LispSymbol(member)` from the WIT file's lower-kebab
  label, bypassing the reader. Trap it fixes: with a HAND-WRITTEN `defpackage` (no `:package`
  option), the `:export` clause is ordinary source text and upcases, so the binding resolved to
  internal `GL::create-shader` while call sites resolved to external `GL:CREATE-SHADER` -- the
  function compiled under one name, was called under another, and silently became a WASM
  call-time stub trapping at `unreachable` only when run (found via
  `examples/browser/webgl-battlefront`, which shares `webgl-common/gl.lisp`). Pinned by
  `PackageResolverTest#bareLowerKebabWitImportNameResolvesAgainstAHandWrittenUppercaseExport`
  and `WitImportDirectiveTest#lowersAFreestandingInterfaceWithTheDefaultCamelCaseFields`.
  Re-evaluation trigger: if `WitImportDirective` starts upcasing its own synthesized names,
  this symmetric branch becomes dead code -- safe to delete once confirmed unused.
- HTTP keys are UPPERCASE: the fetch result plist (`compiler/FetchResponseShape`, keyword =
  `:` + upcased field: `:STATUS`/`:HEADERS`/`:BODY`) and the Clack environment
  (`compiler/ClackEnv`, `:REQUEST-METHOD`/`:PATH-INFO`/...) on all four backends -- a host-ABI
  decision (`.kb/fetch-http.md`, `.kb/http-server.md`).

## Everything else matches EXACTLY (no `equalsIgnoreCase`)

`LispNames.keywordMatches`/`foldKeyword` are deleted; every keyword-argument matcher is
`.equals`, including `LispMacroExpander.requireKeywords`.

- WIT type designators are uppercase internally: `WasmExportCompiler.T_INT`/`T_LONG`/`T_FLOAT`/
  `T_BOOL`/`T_STRING`/`T_S_EXPR`/`T_VOID` = `":INT"`.. `":VOID"`; `KNOWN_TYPES` and every
  per-type switch follow. `WitExportDirective`/`WitImportDirective` synthesize the upcased
  spelling into the lowered `wasm-export`/`wasm-import` forms.
- CLOS method qualifiers are uppercase (`plainTypeName` does not fold; `expandDefmethod` and
  `applicableMethods(..., ":BEFORE"/":AFTER"/":AROUND", ...)`).
- CLOS initargs match by exact name: `LispMacroExpander.buildTypedConstruct`;
  `slot.initargKeyword()` = `":" + baseName` over an upcased slot name (`parseDefclassSlot`,
  `ClosRegistry.seedConditionClass`).
- `AsdfSystems` switches on `key.name()` directly with uppercased labels
  (`:NAME`/`:DEPENDS-ON`/`:COMPONENTS`/`:MODULE`/`:FILE`/`:STATIC-FILE`/...).
- Every hard-coded canonical literal passed to a matcher is upcased (`:TEST`, `:KEY`,
  `:INSECURE`, `:NO-ERROR`, `:EXTERNAL-FORMAT`, `:IF-EXISTS`, `:IF-DOES-NOT-EXIST`, `:UTF-8`,
  `:DEFAULT`, `:SUPERSEDE`, `:CREATE`, `:ERROR`, `:TYPE`, `:READ-ONLY`, `:FORMAT-CONTROL`,
  `:ASYNC-CALL`, `:TASK-RETURN`, `:ASYNC`, `:DROP`, `:METHOD`, `:WORLD`, `:FEATURE`,
  `:REQUIRE`, `:INITFORM`, ...); `Environment.plistGet` HTTP callers pass upper
  (`:HEADERS`/`:METHOD`/`:BODY`).
- The `--component` canonical ABI is UPCASED-ONLY in both directions: `WasmComponentImportCompiler`
  LOWER compares the upcased tag only (no `I32_OR` lowercase twin), and `wit.lisp`'s
  `%wit-result` accepts only the upcased `:OK` envelope head; same for `http.lisp`'s
  `%serve-method-string`, `sockets.lisp`'s `%sock-addr-string` and `usocket.lisp`'s protocol
  check. LIFT emission always spelled cases/plist-keys/envelope heads upcased and is untouched.
  Pinned by `WasmComponentImportCompilerTest` and `ServeMethodCaseComponentE2eTest`.

## Host-facing names derive lowercased

wasm-export/wasm-import default export/import names, `:as` quoted-symbol aliases, wasm-export
`:param-names` symbols and wit-export `:world` symbol designators are `toLowerCase`d (component
labels are lower-kebab; string spellings stay verbatim). The host-facing WIT label derives
lowercased at the single emit point (`componentValType` / `WitEmitter` /
`WitExportDirective.worldName`). An ASDF/quicklisp SYMBOL system designator downcases like ASDF
`coerce-name` (`AsdfSystems.symbolName`; string designators verbatim).

## Tests

`LispReaderTest`/`LispLexerTest` (upcase + escape + designator), `LispEvaluatorTest`
(symbol-name, verbatim intern/find-symbol, runtime read), `JvmLispCompilerTest` /
`WasmLispCompilerIntegrationTest` (compiled read runtimes), ci-spec `read-from-string-upcase`
and `symbol-runtime-api` (native `CiSpecE2eTest`, all four backends), `AssocUtilsUpcaseE2eTest`,
`WitScaffolderTest.theScaffoldedProgramCompilesUnchanged`. Docs:
`doc/{en,ja}/guides/reader-case.md` plus the `symbol-name`/`intern`/`find-symbol`/
`read-from-string` reference pages.
