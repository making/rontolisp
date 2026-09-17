# format: indent Scheme (.scm) sources

Difficulty: Medium

`rontolisp format` walks a directory for `.lisp` and `.asd` only
(`cli/FormatCommand.EXTENSIONS`), and a `.scm` named explicitly is formatted with the
Common Lisp rules (`format/IndentRules`), which damage idiomatic Scheme. Measured
2026-09-17 on the programs under `examples/scheme/`:

- a named `let` breaks its bindings onto their own line (`(let loop` / `((i 0) ...)`):
  the name is taken for the binding list;
- `do` splits its end clause onto its own line even when both fit;
- `cond` clauses lose their alignment under `(cond (first ...)`, and a `define` whose
  name ends in `!` is laid out one parameter per line;
- `define-record-type` joins the constructor onto the first line;
- a quoted list of expressions (a program as data) is packed into filled lines, and a
  trailing argument after a multi-line `lambda` is appended to its last line
  (`(newline)) table)`).

So `examples/scheme/` is indented by hand and is not in the CLAUDE.md format command.

## To do

1. Scheme indent rules: named `let`, `do`, `define-record-type`, `define-values`,
   `let-values`, `case`, `when`/`unless`, `delay`, `cons-stream`; `define` bodies with any
   identifier characters. Read `.kb/formatter.md` first -- the CST front end is shared,
   so check what `#;`, `#true`, `#\x41;` and `[`/`]` do there.
2. Pick the rules by extension (a directory walk includes `.scm`), and keep a `.lisp`
   byte-identical to today's output.
3. Run it over `examples/scheme/` and `scheme-spec.yaml`'s sources; add `.scm` to the
   CLAUDE.md format line.
