# Scheme REPL: a top-level `begin` is split into steps, each fresh-lined

Difficulty: Low

Found while working `.todo/883` (2026-09-19). At the Scheme REPL,

```
(begin (display 3) (display 4) 5)
```

prints `3`, `4` and `5` on three lines; one form should print `34` and then echo `5`, as
the Common Lisp REPL does for `(progn (princ 1) (princ 2))` (`12` then `2`). A macro
whose template is a `(begin ...)` of effects shows the same.

Cause: `SchemeLowering.interact` splices a top-level `begin` (`spliceBegins`, needed so
its definitions are top-level) and returns one `SchemeTopLevel` per spliced form;
`cli/ReplBuffer` runs `freshLine()` after every step. A file is unaffected.

## Plan

- Keep the splice (definitions must stay top-level), but group the forms one typed datum
  produced into ONE `SchemeTopLevel`, echoing the last form's value only when that form
  echoes; or have `ReplBuffer` fresh-line once per typed datum.
- Failing first: a `RontoLispCliTest` REPL transcript (`theSchemeRepl...`) with the
  `begin` above and a macro expanding to one.
