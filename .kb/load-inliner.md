# Compile-time `load` include (`LoadInliner`, cli pkg)

**Compile path only** (`RontoLispCli.compileToFile`, before `PackageResolver`): a **top-level**
`(load "literal.lisp")` is spliced in place with the loaded file's forms (recursively, path-stack
cycle guard), so Pass 1 compiles the loaded `defun`s natively. A computed or nested `load` is left
to the runtime embedded reader. Not idempotent (matches CL). The **interpreter keeps its runtime
`load`** (no inliner), so no double definition.

## Paths and options

- A relative `load` resolves against the loading file's directory, falling back to CWD for
  entry/REPL -- both the inliner (`baseDir` arg) and the runtime `load`
  (`LispEvaluator.setLoadBaseDir`, a `loadDirStack`) go through `SourceLoader.resolve` /
  `parentDir`, whose null/empty base dir skips all `java.nio` math so the no-FS browser loader is
  untouched.
- CL's four keyword options are SPLICED like a bare `load` when path and every option value are
  literal -- none changes WHICH forms load. `:if-does-not-exist` is decidable here (a false value
  over an unreadable file becomes `nil`). A COMPUTED option value is left to the runtime `load`
  (`LispMacroExpander.lowerLoadOptions`, `.kb/read-load-streams.md`).

## `require` / `provide`

Same pass, threading a `provided` set through the recursion. `(provide NAME)` records NAME and is
consumed; `(require NAME ["path.lisp"])` splices `NAME.lisp` (or the explicit path) unless NAME is
already in the set.
- `require` does NOT auto-mark -- the `(provide NAME)` inside the required file does, so
  provide-first files terminate mutual requires; path cycles hit the `loading` stack guard.
- Module name must be a literal designator (keyword, quoted symbol, string); anything else is a hard
  `IllegalStateException`. Any `require`/`provide` surviving the pass is an
  `UnsupportedOperationException` in `Jvm/WasmExprCompiler.compileCons`.
- Interpreter: runtime functions next to `load` in `LispEvaluator` (`providedModules`, shared
  `loadFile`), in `PackageRegistry.CL_FUNCTIONS` so `#'require` works. `*modules*` is not exposed.

## Load context (`*load-pathname*` / `*load-truename*`)

`spliceFile` brackets every spliced file with `(%begin-file PATHNAME TRUENAME)` / `(%end-file)`:
the spelling `load` was called with, and the resolved path. An ASDF COMPONENT is spliced under its
RESOLVED path for both, making `*load-pathname*` equal `asdf:component-pathname` -- the correlation
rove's suite-to-file map needs, so pin the two together.

Markers are emitted unconditionally. `LispMacroExpander.lowerLoadContextMarkers` -- called by BOTH
compile backends and `NoGcWasmCompiler` first, before package resolution -- lowers a bracket to
top-level `(setq cl:*load-pathname* ...)` with the ENCLOSING file's values assigned back at
`%end-file`, and DROPS the brackets whole when the program mentions neither variable. Names are
`cl:`-qualified because the pass runs BEFORE the resolver. `PackageResolver` consumes the bracket,
keeping the file PATH out of `LibraryDefunPruner`'s reference scan.
`*compile-file-pathname*`/`*compile-file-truename*` stay nil (`.kb/asdf.md`).

**A SECOND consumer at READ time**: `#.` is wrapped in a `(%read-eval datum)` marker resolved per
top-level form (`.kb/reader-features.md`), before the lowered `setq` statements run. So
`UserMacroExpander`'s top-level loop tracks `%begin-file`/`%end-file` depth as it tracks
`%begin-system`/`%end-system` and pushes the bracket's two strings as dynamic bindings
(`LispEvaluator.pushLoadContext`/`popLoadContext`); one shared accessor,
`LispMacroExpander.loadContextValues`, keeps read-time and run-time values from drifting. The
binding covers the whole span, so a spliced SYSTEM's replayed top-level form
(`.kb/defmacro-backquote.md`) sees it too. The ENTRY file is not bracketed on either path, so a `#.`
at the program's top level reads nil. This is what makes `(or *compile-file-pathname*
*load-truename*)` + `merge-pathnames` + `with-open-file` work (cl-mustache's `version.lisp-expr`).

## Reader errors

Every file the pass reads (`spliceFile`, incl. ASDF/Quicklisp components) is read with its path
passed to `LispReader`, so a parse error inside a multi-file library carries `file:line:column:`.
Cons-identity rule `CompileTimePathnameFolder` had to honour: `.kb/source-positions.md`.

## Tests

`LoadInlinerTest` (incl. `#inlinesALoadCarryingLiteralKeywordOptions`,
`#aMissingFileUnderIfDoesNotExistNilBecomesNil`, `#aComputedLoadOptionIsLeftToTheRuntimeLoad`,
`#readEvalInALoadedFileSeesThatFilesLoadContext`,
`#aLoadContextBracketCostsNothingWhenTheProgramNeverReadsIt`), `LispEvaluatorTest`,
`Jvm/WasmLispCompiler*Test`, `LoadContextE2eTest` (all four backends, fixture
`src/test/resources/load-context-demo/src/data.lisp-expr`).
