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
and the browser playground have no file and read the default; the playground's compile
buttons keep the error-mode read (`readStrict`) because their reduced frontend has no
marker-resolution pass.

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
metadata (tolerant `#.`-skipping read) and leading-`defpackage` scans. A second
language extends `forFile` (and `isSourceFile`) and lowers to the same core forms;
what else it needs to own (REPL continuation, error positions, a formatter) is decided
when it asks for it, by adding a method to the seam.

## Tests

`SourceLanguageSeamTest` (no class outside the seam reads user source through
`LispReader` directly; no stale exemptions), `RontoLispCliTest`,
`JvmSourceCompilerTest`.
