# Compile-time `load` include (`LoadInliner`, cli pkg)

**Compile path only** (`RontoLispCli.compileToFile`, before `PackageResolver`): a **top-level**
`(load "literal.lisp")` is spliced in place with the loaded file's forms (recursively, path-stack
cycle guard), so Pass 1 compiles the loaded `defun`s natively. A computed or nested `load` is left
to the runtime embedded reader -- unless the loading file's context folds it (below). Not idempotent (matches CL). The **interpreter keeps its runtime
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

## The portable idiom: a path built from the load context

Standard CL resolves a relative `load` against the working directory, so a program that must run
on SBCL too writes `(load (merge-pathnames "src/a.lisp" *load-truename*))`. `expandInto` carries
an `Origin` (base dir + the file's pathname/truename) and, when a `load`/`require` path is not a
literal, asks `CompileTimePathnameFolder.foldLoadPath`: the four variables are bound as at run
time (the file's two, nil outside every file; both compile-file ones nil), and `reduce` runs with
`loadPath` set, which adds `or` and two-argument CL `merge-pathnames` to its shapes. Gated, not
general: a general fold of those would start bundling `with-open-file` over a computed path, and
`collectWrittenPaths` only matches LITERAL output paths. A reduced path splices like a literal one;
anything else stays a run-time `load` (the `require` path a compile error, as before).

The fold answers what the run time answers only because `*load-truename*` is ABSOLUTE: the runtime
`load` resolves a relative path against the loading file's directory, so a CWD-relative truename
merged and loaded resolved the directory twice (`app/app/src/a.lisp`). Both paths take the truename
from `SourceLoader.canonicalPath` (absolute, links resolved -- `truename`'s answer;
identity for a no-filesystem loader); `*load-pathname*` stays the spelling, so merging onto it is
not portable here (nor on a CL whose `*default-pathname-defaults*` is `#P""`).

**The entry file is a file being loaded** (since 2026-10-24): a Common Lisp entry gets the same
bracket (`LoadInliner.inline`'s `entryFile`, passed by `CompileFrontend.run` for
`SourceLanguage.COMMON_LISP` only) and the interpreter binds it (`RontoLispCli.interpret` ->
`pushLoadContext`), pathname = the path as given, truename = canonical. That is what SBCL's
`--script` does, and what makes the fold and the run time agree for the entry's own forms. A
program with no file (stdin, `-e`, `rontolisp test`, every in-process test driving `LispEvaluator`
or `JvmSourceCompiler` without an entry) has none, so "nil outside every load" still holds there.
Cost: none when unread -- the bracket is dropped with the others. A compiled program that READS
the variables bakes the build machine's absolute path; a WASM program opening a file through it at
run time needs that path preopened (relative truenames used to work under `--dir .`).

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
(`.kb/defmacro-backquote.md`) sees it too. A Common Lisp ENTRY file is bracketed too (above), so a `#.`
at its top level reads the entry's context; a program with no file reads nil. This is what makes `(or *compile-file-pathname*
*load-truename*)` + `merge-pathnames` + `with-open-file` work (cl-mustache's `version.lisp-expr`).

## Reader errors

Every file the pass reads (`spliceFile`, incl. ASDF/Quicklisp components) is read with its path
passed to `LispReader`, so a parse error inside a multi-file library carries `file:line:column:`.
Cons-identity rule `CompileTimePathnameFolder` had to honour: `.kb/source-positions.md`.

## Tests

`LoadInlinerTest` (incl. `#inlinesALoadCarryingLiteralKeywordOptions`,
`#aMissingFileUnderIfDoesNotExistNilBecomesNil`, `#aComputedLoadOptionIsLeftToTheRuntimeLoad`,
`#readEvalInALoadedFileSeesThatFilesLoadContext`,
`#aLoadContextBracketCostsNothingWhenTheProgramNeverReadsIt`,
`#aLoadPathBuiltFromTheLoadContextSplicesLikeALiteral`,
`#readEvalLoadContextRestoresTheEnclosingFileAndIsTheEntryFilesAtTopLevel`), `LispEvaluatorTest`,
`Jvm/WasmLispCompiler*Test`, `LoadContextE2eTest` (all four backends, fixture
`src/test/resources/load-context-demo/src/data.lisp-expr`;
`#thePortableLoadIdiomSplicesTheEntrysSourcesOnEveryBackend` runs the CLI on an entry file and
the compiled artifacts with its `src/` deleted), `lispresen/check.sh` (SBCL leg).
