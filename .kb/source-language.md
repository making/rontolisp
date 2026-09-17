# The source-language seam (`SourceLanguage`, eval pkg)

A language is something that PRODUCES core forms and then joins the existing pipeline:
`source text --[ language: read + lower ]--> List<LispVal>` (the de-facto IR the
`reader`/`macro`/`compiler`/`codegen`/`eval` packages share) `-> CompileFrontend.expand`
on the compile path, `LispEvaluator` on the interpreter. One type owns "source text
-> forms" (`eval/SourceLanguage.read`, which also owns the `#.` marker decision via
`usesReadEvalMarkers`), and one method picks the language (`forFile`: the file's
extension, with the `--source-language` CLI override winning for the entry source).

The pick is PER FILE: a `(load ...)`ed file is read in the language ITS extension
names, so one program may mix languages file by file (`LoadInliner.spliceFile` on the
compile path, `LispEvaluator.loadFile` on the interpreter). The entry source's
language is its extension or the override (`CompileFrontend.run`,
`RontoLispCli.interpret`, `JvmSourceCompiler.sourceLanguage` for embedders). The REPL
has no file: its language is the override, else the default. The browser playground has
no language pick yet and reads the default; its compile buttons keep the error-mode read
(`readStrict`) because their reduced frontend has no marker-resolution pass.

## Reading without a file: `SourceSession`

A consumer that reads one buffer at a time asks the LANGUAGE four things, through
`eval/SourceSession` (one per REPL): `isComplete` (continue the line or read now), `read`
(core forms per top-level form, plus whether its value is worth echoing), `print` (the
language's own `write`) and `prompt` (`CL-USER> ` follows the current package, `scheme> `
has none). It is a session because a language may carry state between buffers; Scheme
does (`.kb/scheme-frontend.md`, "A session"), Common Lisp keeps none here. `cli/ReplBuffer`
is the one consumer today, shared by both REPL drivers; the playground's `evalLine` is the
same shape and takes this seam when it gains a language pick.

The entry-language override is validated where it is parsed (an unknown name fails
fast); loaded files always pick by extension, so the override never leaks into them.
`isSourceFile` answers the `rontolisp test` question (a missing `foo.lisp` is an error,
a missing `foo` is a system to look up); `fileNameForModule` / `defaultExtension`
spell the `require` default mapping and the package-inferred sub-system file in one
place. `FormatCommand` does NOT consult the seam: the formatter is Common Lisp only
until a language brings its own.

NOT user source, so NOT through the seam (pinned by `SourceLanguageSeamTest`, next to
`PackageCycleTest`): library source shipped in the jar -- or a form the implementation
synthesizes itself -- which is Common Lisp whatever the user's language is (every
`*Library` splice, `ShimLibraries`, `UiopLibrary`, the four `macro` readers);
`Environment`'s runtime `read`/`read-from-string` of DATA; `AsdfSystems`' `.asd`
metadata (tolerant `#.`-skipping read) and leading-`defpackage` scans. A further
language extends `forFile` (and `isSourceFile`) and lowers to the same core forms;
what else it needs to own (error positions, a formatter) is decided when it asks for it,
by adding a method to the seam; what a REPL needs is `SourceSession`'s four.

## The second language: `SCHEME` (experimental)

`.scm`, or `--source-language scheme` (`scm`). `read` hands the text to
`am.ik.rontolisp.scheme.Scheme.read` -- reader, desugaring and lowering to core forms in
one step, with no `#.` and no reader features -- and everything downstream is unchanged
(`.kb/scheme-frontend.md`). `CompileFrontend.run` refuses the entry language under
`--no-gc`. `defaultExtension()` is per language; `fileNameForModule` and the
package-inferred sub-system file stay Common Lisp.

## Tests

`SourceLanguageSeamTest` (no class outside the seam reads user source through
`LispReader` directly; no stale exemptions), `RontoLispCliTest` (the `repl...` and
`theSchemeRepl...` transcripts),
`JvmSourceCompilerTest`.
