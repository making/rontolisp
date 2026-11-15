# j50. Starlark: the REPL session, the playground and the doc Run cells

Difficulty: Medium

A language without a file is asked four things through `SourceSession`
(`.kb/source-language.md`, "Reading without a file"): `isComplete`, `read`, `print`,
`prompt`. Today its constructor refuses Starlark (`Starlark is read a file at a time: it has
no REPL yet`, pinned by `SourceLanguageTest` and `RontoLispCliStreamsTest`), which is what
the REPL and the playground answer.

- `isComplete` is the new part for an indentation language: a block is complete at a blank
  line, an open bracket continues. Neither existing language has that rule.
- `print` is Starlark `repr`; a statement and a `None` value echo nothing.
- The session carries the globals defined by earlier buffers, as Scheme's does: the
  lowering resolves a name against ONE file's globals and refuses any other
  (`StarlarkLowering.reference`).
- An error in one buffer must not leave a list counted as being iterated for the next: a
  loop gives its count back on a normal exit only (`.kb/starlark-frontend.md`, "The
  run-time library's rules").
- The browser playground's language pick and the documentation site's fenced Run cells
  (`.kb/source-language.md`, "The browser"; `.kb/documentation-site.md`). `DocExamplesTest`
  already runs a ` ```starlark ` fence as a whole program and asserts its output;
  `RunnableBlockTransformer` leaves it static, and the playground's compile chain
  (`RontoPlayground.frontend`) has no `StarlarkLibrary.process`.

Test: one REPL transcript in `RontoLispCliTest` beside the Scheme and Clojure ones, and
`PlaygroundReplTest`.
