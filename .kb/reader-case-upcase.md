# Reader case: uppercase-canonical (upcase, no fold)

The reader upcases unescaped symbol characters like Common Lisp's `:upcase` readtable
case, and the upcased name IS the canonical spelling -- there is no fold back to a
lowercase form. `foo` and `FOO` both read as `FOO`; `defun`/`DEFUN` read as `DEFUN`,
`list` as `LIST`; `t`/`nil` as `T`/`NIL`; every keyword is upper case (`:FOO`); the
built-in packages' own members are upper case too (`rl:fetch` reads `RL:FETCH`,
`ql:quickload` reads `QL:QUICKLOAD`). Nothing downstream (resolver, splice scanners,
evaluator, all backends) folds a name to lowercase.

Escape handling is per-character in `LispLexer.readSymbol` (the ONLY place escape info
exists): an escaped character (`\c`, or any char inside `|...|`) is kept verbatim and
NOT upcased, so `|car|` reads the distinct symbol `car`, NOT `CAR` -- CL-faithful (a
pipe-escaped lowercase name is its own symbol). The number-fallback token
(`numberFallbackSymbol`, e.g. `1+`) upcases the whole run like any other symbol.

**Decision record.** This replaced the earlier *lowercase-canonical + fold-on-top*
model (Approach B: keep lowercase canonical, layer CL's upcase reader on top as a
fold). The cutover to **Approach A2 (uppercase-canonical, delete the fold)** landed
2026-07-20; Phase 1/2 (interpreter + JVM) committed `0125621`, Phase 3 (WASM) + the
test/doc/kb sweep followed. The fold machinery was DELETED: the `UpcaseSymbols` class
(`canonicalize`/`foldableBareNames`/the baked blobs), `Features.INTERNAL` +
`preserveCase`/`preservingCase`, the JVM read runtime's `_canon`/`_delimContains`
(`JvmReadRuntimeBuilder`), and the WASM `emitCanon` in-place byte fold. There is no
longer a "case-preserving island" for rontolisp's own lowercase-authored sources --
the spliced libraries, the prelude, the shims and `.asd` data all read upcased like
everything else, and the Java-side matchers (splice scanners, pruner, wrapper defs)
match the upcased names. Full history + the ci-spec regen tool: `.todo/156`.

**Runtime symbol API is VERBATIM.** `intern`/`make-symbol`/`find-symbol` take the name
as given: `(intern "TIME")` names `TIME`, `(intern "time")` names the distinct symbol
`time`, `(find-symbol "car")` is `NIL` (the standard symbol is named `CAR`),
`(make-symbol "X")` twice is `eq` (no intern table -- identity is the name; see
`symbol-runtime-api.md`). `LispEvaluator`'s `intern`/`find-symbol` pass `str.value()`
straight through (`intern` still homes a bare name into the resolver's current package
via `internSpelling`). The compiled backends' `intern` built-in is verbatim too, so
the old `(intern "<cl-name>")` interp-vs-compiled divergence is closed.

**Runtime `read`/`read-from-string` upcase on ALL FOUR backends** (no fold): so
`(read-from-string "foo")` is `FOO`, `(eq (read-from-string "list") 'list)` is `T`.
The interpreter reads with `Features.INTERPRETER` (the lexer's `:upcase`). The compiled
backends upcase the token in their embedded reader runtimes -- the JVM's `_classify`
does `token.toUpperCase(Locale.ROOT)` (no baked fold set), the WASM read runtime upcases
the token bytes in place before `_intern` (length-preserving, so the intern/nil/t path
is unchanged). Non-ASCII code chars on WASM are a separate limitation (`.todo/153`).

**`symbol-name`/print report the stored spelling, which is upcased** (`'foo` -> `FOO`,
`(symbol-name 'foo)` = `"FOO"`, `(symbol-name 'car)` = `"CAR"` -- the CL answer; the OLD
"standard names are lowercase" deviation is GONE). `symbol-name` still strips the
package marker of a keyword/gensym (`LispSymbol.displayName`, shared with `princ`/`~A`/
`string`; `prin1`/`print` keep the stored spelling). Any Java-side name synthesis from a
user symbol goes through `LispMacroExpander.affixFor` (defstruct `MAKE-PT`/`PT-P`, class
`%MAKE-`+base), which case-matches its base -- now always upper case.

**Case-tolerance seams that REMAIN** (these are NOT the fold -- they bridge upcased Lisp
data with lower-kebab WIT / host-ABI names, or give CL-style case-insensitive keyword
args; keep them):

- `LispNames.keywordMatches` (equalsIgnoreCase) and `foldKeyword` (lowercased `switch`
  scrutinees): the builtin keyword-argument matchers accept `:TEST` where `:test` is
  meant, at the helper choke points, the `findKeywordValue` copies, and the option
  `switch`es (defstruct/defclass/define-condition, defpackage, open/parse-integer/...,
  wasm-export/wit-import directives, AsdfSystems clauses). Keyword DATA is never re-cased.
- `LispEvaluator.instanceSlotCell` compares slot base names with `equalsIgnoreCase`: a
  Java-side caller spells a built-in condition slot lowercase (`conditionSlotValue`
  passes `"format-control"`) while an upcase-read condition registers `FORMAT-CONTROL`
  (the same reconciliation `LispMacroExpander.expandConditionSlotReader` makes for the
  compiled backends). This is why `simple-condition-format-control` returns the control
  string, not `NIL`.
- `PackageResolver.resolveUnqualified`/`resolveQualified` one-shot lowercase retry:
  bridges an upcased reference to a `wit-import` package's lower-kebab member once the
  member is bound (`GL:CREATE-SHADER` reaching a wit-imported `gl:create-shader` defun).
- HTTP plist keys are UPPERCASE (`compiler/HttpPlistShape`: keyword = `:` + upcased field):
  all four backends emit/read `:STATUS`/`:HEADERS`/`:BODY`..., a host-ABI decision (see
  `http-plist-shape` in the kb index), kept.

**Removed seam (2026-07-21, the `--component` canonical-ABI dual-compares).** The
canonical-ABI boundary is now UPCASED-ONLY in both directions. The LIFT always spelled
every variant/enum case, record plist-key and `result` envelope-head upcased (matching
upcased user data); the LOWER used to dual-compare a tag against BOTH the lowercase WIT
name (`:get`) and its upcased twin (`:GET`, via `I32_OR` in `WasmComponentImportCompiler`),
and `wit.lisp`'s `%wit-result` accepted both `:ok`/`:OK` envelope heads -- to also admit
the lowercase tag the *pre-A2 case-preserving* library sources constructed. Under A2 those
sources (`http.lisp`'s `%fetch-method-variant`, `sockets.lisp`'s `%sock-addr`, ...) read
upcased too, so the lowercase arm went dead and was dropped: the four
`WasmComponentImportCompiler` LOWER dual-compares and `%wit-result` now match the upcased
spelling only. The same collapse applied to the lift-facing Lisp consumers whose two arms
had become the *identical* upcased symbol under A2 -- `(or (eq x :foo) (eq x :FOO))` is
`(or A A)` -- in `http.lisp`'s `%serve-method-string`, `sockets.lisp`'s
`%sock-addr-string`, and `usocket.lisp`'s protocol check. Output is byte-identical on
every backend; the compiled `.wasm`/`.class` shrink by the dropped instructions. The LIFT
emission is untouched. Pinned by `WasmComponentImportCompilerTest` (the lift stays
upcased-only, no lowercase spelling emitted) and `ServeMethodCaseComponentE2eTest` (every
HTTP method round-trips through the real `wasi:http` `method` variant). `.todo/157`.

**Host-facing names derive lowercased**: wasm-export/wasm-import default export/import
names and `:as` quoted-symbol aliases, wasm-export `:param-names` symbols, and wit-export
`:world` symbol designators are `toLowerCase`d (component labels are lower-kebab; string
spellings stay verbatim). An ASDF/quicklisp SYMBOL system designator downcases like ASDF
`coerce-name` (`AsdfSystems.symbolName`; string designators stay verbatim).

Pinned by: `LispReaderTest`/`LispLexerTest` (upcase + escape + designator cases),
`LispEvaluatorTest` (symbol-name, intern/find-symbol verbatim, runtime read),
`JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest` (compiled read runtimes),
the `read-from-string-upcase` and `symbol-runtime-api` ci-spec cases (native
`CiSpecE2eTest`, all four backends), and `AssocUtilsUpcaseE2eTest` (the assoc-utils
`with-keys` name-synthesis idiom). Docs: `doc/{en,ja}/guides/reader-case.md` + the
`symbol-name`/`intern`/`find-symbol`/`read-from-string` reference pages.
