# One seam from user source to forms, so a second source language has a place to enter

Difficulty: Medium

The pipeline assumes one source language, and the assumption is not written down in one
place -- it is restated at every site that turns USER source text into `List<LispVal>`.
Measured on 2026-09-17 (`.todo/artefacts/824-one-seam-from-user-source-to-forms/census.sh`):

| site | what it reads |
|---|---|
| `cli/CompileFrontend.java:175` | the compile path's entry file |
| `cli/LoadInliner.java:387` | a `(load ...)`ed file, compile path |
| `cli/RontoLispCli.java:525,531` | the interpreter's entry file |
| `cli/ReplBuffer.java:74` | a REPL entry |
| `web/RontoPlayground.java:114,188` | the playground, interpreter and compile |
| `eval/LispEvaluator.java:3352,3357` | a `(load ...)`ed file, interpreter |

Every one of them hand-copies the same decision,
`source.contains("#.") ? readAllWithReadEvalMarkers(...) : readAllFromString(...)`. That is
the restated-pipeline bug `CLAUDE.md` forbids for `CompileFrontend.expand`, one step earlier:
a rule about how source is read that nothing owns. It is worth fixing with one language.

The `.lisp` extension is hard-coded at five sites (`TestCommand:97`, `FormatCommand:29`,
`LoadInliner:308`, `LispEvaluator:3054`, `AsdfSystems:1537`).

## The shape to build

A second language does NOT sit beside `reader`/`macro`/`compiler`/`codegen`/`eval`. The
core forms those packages consume are the de-facto IR; a language is something that
PRODUCES them and then joins the existing pipeline:

```
source text --[ language: read + lower ]--> List<LispVal> (core forms)
                                              -> CompileFrontend.expand -> backends
                                              -> LispEvaluator
```

So this item introduces the seam and moves nothing else:

1. One type that owns "source text -> forms" (working name `SourceLanguage`):
   `List<LispVal> read(String source, Features features, @Nullable String file)`. The
   Common Lisp implementation owns the `#.` decision. Replace the sites in the table.
   It must be reachable from `cli`, `web` AND `eval` (the interpreter's `load`), so it
   lives at or below `eval`; `Environment.java:6226` (`read-from-string` and friends) is a
   RUNTIME read of data, not user source, and stays on `LispReader`.
2. One place that picks the language -- from the file extension, with a CLI override --
   and hands it down. `load` of a file picks by that file's extension, so a program may
   mix languages per file. The five `.lisp` sites consult it where they mean "a source
   file" (`FormatCommand` does not: the formatter is Common Lisp only until a language
   brings its own).
3. A structure test, next to `PackageCycleTest`, pinning that no class outside the seam
   calls `LispReader.readAll*` on user source. The 37 callers in `eval` and the 4 in
   `macro` read LIBRARY source shipped in the jar; that source is Common Lisp whatever the
   user's language is, and they are exempt by an explicit list with the reason stated.
4. `CLAUDE.md`: state the model above in "Architecture". Add a package to the dependency
   graph only when it exists.

## Deliberately out of scope

- **No package renames or moves.** `reader` -> `frontend.cl.reader` and the like would
  misdescribe the architecture (`macro`, `compiler` and the library splices are shared
  core, not one language's front end), would touch 135 test files that call `LispReader`
  directly and the class paths 126 `.kb` files point at, and would conflict with every
  session in flight -- for no behavior. What else a language needs to own (REPL
  continuation, error-position mapping, a formatter) is decided when a second language
  asks for it, by adding a method to the seam.
- The root package mixes AST types with Common Lisp registries (`ClosRegistry`,
  `PackageRegistry`, `UiopExports`, `ClConstants`). A language that lowers to core forms
  does not need them separated; that tidy-up is its own item if anyone wants it.

## Done when

Behavior is byte-identical (full `./mvnw test`, `-Pweb compile`, `-Pnative` package, the
native `CiSpecE2eTest` leg), the structure test is red when a new direct call is added,
and the seam is recorded in a `.kb` file with the exempt-caller rule.

Unblocks `.todo/825`.
