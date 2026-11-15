# j50. Starlark: the REPL session, the playground and the doc Run cells

Difficulty: Medium

After `.todo/j46`. A language without a file is asked four things through `SourceSession`
(`.kb/source-language.md`, "Reading without a file"): `isComplete`, `read`, `print`,
`prompt`.

- `isComplete` is the new part for an indentation language: a block is complete at a blank
  line, an open bracket continues. Neither existing language has that rule.
- `print` is Starlark `repr`; a statement and a `None` value echo nothing.
- The session carries the globals defined by earlier buffers, as Scheme's does.
- The browser playground's language pick and the documentation site's fenced Run cells
  (`.kb/source-language.md`, "The browser"; `.kb/documentation-site.md`).

Test: one REPL transcript in `RontoLispCliTest` beside the Scheme and Clojure ones, and
`PlaygroundReplTest`.
