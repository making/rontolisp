# Compile-time `load` include (`LoadInliner`, cli pkg)

**Compile path only** (`RontoLispCli.compileToFile`, before the compilers' `PackageResolver`
pass): a **top-level** `(load "literal.lisp")` is spliced in place with the loaded file's
forms (recursively, path-stack cycle guard), so Pass 1 compiles the loaded `defun`s natively.
A computed or nested `load` is left untouched (runs at runtime via the embedded reader). Not
idempotent (each `load` includes again, matching CL). The **interpreter keeps its runtime
`load`** (no inliner), so no double definition. Tests: `LoadInlinerTest`.

## File-relative paths
A relative `load` resolves against the loading file's directory (the entry file at top level),
falling back to CWD for entry/REPL -- applied to BOTH the inliner and the runtime `load` via
`SourceLoader.resolve`/`parentDir` (a null/empty base dir skips all `java.nio` math, so the
no-FS browser loader is untouched). Interpreter threads a `loadDirStack`
(`LispEvaluator.setLoadBaseDir`, seeded by `RontoLispCli`, pushed per nested `load`); the
inliner takes a `baseDir` arg.

## Keyword options
A top-level `load` with CL's keyword options is SPLICED like a bare one when path and every
option value are literal -- none of the four changes WHICH forms load. `:verbose`/`:print`
ask for progress a splice does not produce; `:external-format` selects a decoder that does not
exist (every backend reads UTF-8); `:if-does-not-exist` is decidable here -- for a false value
over an unreadable file the form becomes `nil`. A COMPUTED option value is left to the runtime
`load`, which understands the same four (`LispMacroExpander.lowerLoadOptions`,
`.kb/read-load-streams.md`). Tests:
`LoadInlinerTest#inlinesALoadCarryingLiteralKeywordOptions`,
`#aMissingFileUnderIfDoesNotExistNilBecomesNil`, `#aComputedLoadOptionIsLeftToTheRuntimeLoad`.

## `require` / `provide`
Same pass; a `provided` set is threaded through the inline recursion.
- `(provide NAME)` records NAME and is consumed (replaced by a quoted symbol, like
  `in-package`).
- `(require NAME ["path.lisp"])` splices `NAME.lisp` (or the explicit path) like `load`,
  unless NAME is already in the set (diamond case), when it is consumed without loading.
- `require` does NOT auto-mark: the `(provide NAME)` inside the required file marks the set,
  so provide-first files terminate mutual requires; path cycles hit the `loading` stack guard.
- Module name must be a literal designator (keyword, quoted symbol, string); a bare symbol or
  computed expression is a hard `IllegalStateException` -- the compiled runtime reader does
  not know require/provide.
- Any `require`/`provide` left after the pass is an `UnsupportedOperationException` in
  `Jvm/WasmExprCompiler.compileCons`.
- Interpreter: runtime functions next to `load` in `LispEvaluator` (`providedModules` set,
  shared `loadFile`); in `PackageRegistry.CL_FUNCTIONS` so `#'require` works. Both return the
  module name as a symbol; duplicate provide is a no-op. `*modules*` is not exposed.
- Tests: `LoadInlinerTest`, `LispEvaluatorTest`, `Jvm/WasmLispCompiler*Test`.

## Load context (`*load-pathname*` / `*load-truename*`)
`spliceFile` brackets every spliced file with `(%begin-file PATHNAME TRUENAME)` /
`(%end-file)`: the spelling `load` was called with, and the resolved path. An ASDF COMPONENT
is spliced under its RESOLVED path for both (as real ASDF and the interpreter's `loadFile`
do), which makes `*load-pathname*` equal `asdf:component-pathname` -- the correlation rove's
suite-to-file map needs, so pin the two together.

Markers are emitted unconditionally (this pass cannot know whether anything reads the
variables; library splices run after it). `LispMacroExpander.lowerLoadContextMarkers` --
called by BOTH compile backends and `NoGcWasmCompiler` first, before package resolution --
lowers a bracket to top-level `(setq cl:*load-pathname* ...)` with the ENCLOSING file's values
assigned back at `%end-file`, and DROPS the brackets whole when the program mentions neither
variable (pinned by `aLoadContextBracketCostsNothingWhenTheProgramNeverReadsIt`). The restore
is static because the bracket is.

Names are `cl:`-qualified because the pass runs BEFORE the resolver and a spliced file's forms
sit inside its own `in-package`. `PackageResolver` consumes the bracket like the system one,
which keeps the file PATH out of `LibraryDefunPruner`'s reference scan (that scan reads the
RESOLVED copy, where the marker is already a quoted symbol, so a path containing a bundled
definition's name cannot keep it alive). `*compile-file-pathname*`/`*compile-file-truename*`
stay nil (there is no `compile-file`); why they stay nil at READ time too: `.kb/asdf.md`.
Tests: `LoadInlinerTest`, `Jvm/WasmLispCompiler*Test`, `LoadContextE2eTest` (all four
backends over a real system, incl. `component-pathname`).

## The same bracket at READ time -- a SECOND consumer
The lowering above produces `setq` STATEMENTS, but a `#.` datum of the spliced file is already
evaluated by then: on the compile path `#.` is wrapped in a `(%read-eval datum)` marker that
`UserMacroExpander.expand` resolves against its macro-time evaluator, per top-level form
(`.kb/reader-features.md`). So `UserMacroExpander`'s top-level loop tracks
`%begin-file`/`%end-file` depth as it tracks `%begin-system`/`%end-system`, and pushes the
bracket's own two strings as dynamic bindings there
(`LispEvaluator.pushLoadContext`/`popLoadContext`). One shared accessor,
`LispMacroExpander.loadContextValues`, keeps read-time and run-time values from drifting.
- The binding covers the whole span, so a spliced SYSTEM's replayed top-level form
  (`.kb/defmacro-backquote.md`) sees the load context too.
- No unwind protection / re-entrancy concern: the macro-time evaluator is local to the pass
  and dies with a failed compile, and `LoadInliner` emits both halves or neither.
- The ENTRY file is not bracketed on either path, so a `#.` at the program's top level reads
  nil -- matching `RontoLispCli.interpret`, which never enters `loadFile` for it.
- This makes `(or *compile-file-pathname* *load-truename*)` + `merge-pathnames` +
  `with-open-file` work: the portable way a library reads a data file shipped beside its
  source (cl-mustache's `version.lisp-expr`). Pinned by
  `LoadInlinerTest#readEvalInALoadedFileSeesThatFilesLoadContext` /
  `#readEvalLoadContextRestoresTheEnclosingFileAndIsNilOutsideEveryLoad` and by
  `LoadContextE2eTest` (fixture `src/test/resources/load-context-demo/src/data.lisp-expr`,
  read-time capture per shape -- component, nested load, plain load -- asserted EQUAL to the
  run-time pair on all four backends).

## Reader errors
Every file the pass reads (`spliceFile`, incl. ASDF/Quicklisp components) is read with its
path passed to `LispReader`, so a parse error inside a multi-file library carries
`file:line:column:` instead of a line of the flattened entry program -- as does a
macro-expansion or compile error raised later. Full mechanism, incl. the cons-identity rule
`CompileTimePathnameFolder` had to honour: `.kb/source-positions.md`.
