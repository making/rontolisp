# g73: lispresen highlights only Lisp, Scheme, Clojure and console code

Difficulty: Medium

`lispresen/src/highlight.lisp` has one tokenizer for the Lisp family and a line rule for
`console`; a `(code :lang "java" ...)`, `"python"`, `"javascript"`, `"sql"`, `"json"`,
`"yaml"` or `"bash"` block is set as plain `code-fg` text. A talk that shows rontolisp
next to the Java it compiles to, the shell command that builds it or the JSON a server
returns shows those blocks as a flat grey panel next to colored Lisp, and keywords,
strings and comments do not stand out from the back of the room.

## Value

- For the user: code in the languages a technical talk most often shows next to Lisp
  reads as code. Unhighlighted code is the most visible difference between a Lisp block
  and any other on the same slide.
- Design: highlighting only colors spans; widths are the monospace table's, so the fit
  solver and the layout are untouched. The tokenizer runs at render time in Lisp, so the
  output stays byte-identical on every backend and the page needs no script or network.
  The highlight colors are the theme's existing four (`hl-str`, `hl-kw`, `hl-def`,
  `hl-const`) plus `code-muted` for comments, already contrast-checked.
- Cost: a small table-driven tokenizer (line comments, block comments, string and
  character literals with escapes, numbers, a keyword list per language) covers the
  C family (Java, JavaScript/TypeScript, C, Go, Rust, Kotlin), Python, shell, SQL, JSON
  and YAML; a full grammar per language is not the aim.

## Shape

- Per language: comment delimiters, string delimiters (including Python's triple quotes,
  Java text blocks and template literals as plain strings), the keyword list (`hl-kw`),
  literal constants (`true`, `null`, `None` as `hl-const`), numbers. Unknown languages
  stay plain, as today.
- Only the Lisp family is runnable; other languages are never run, whatever `:run` says
  (check what `:run` on them does today and keep it an error or a no-op consistently).
- The player's editor re-highlights edited Lisp code with `HIGHLIGHT` in `player.lisp`;
  other languages are not editable, so the JavaScript side needs nothing, unless editing
  is extended to them.
- `:steps`, `:at` callouts and the code morph work on the highlighted lines as they do
  for Lisp (the line spans are closed and reopened around highlight spans the same way).

## Verification

- Unit tests in `lispresen/test/lispresen-test.lisp`: no test covers highlighting yet,
  so add one `deftest` driven by a table of (language, source, expected spans), one row
  per language (the Lisp family and `console` included), covering a comment marker
  inside a string and a string inside a comment.
- `lispresen/check.sh`: byte-identical output on every backend.
- Chrome and Safari: a slide with a Java, a Python and a shell block in each built-in
  theme; the runtime check makes no correction.
- README.md (`code` row) and DESIGN.md (Runnable code / highlighting) list the languages.
