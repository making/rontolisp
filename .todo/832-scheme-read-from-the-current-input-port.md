# Scheme: `read` -- a run-time datum reader on the current input port

Difficulty: High

Split off `.todo/826` (its ports row) for `.todo/828`. `(read)` is "The function read is
undefined" and `read` in value position "The variable read is unbound" (the corpus passes
it as a primitive: `(list 'read read)`). 31 corpus files use it; 24 end in `(driver-loop)`.
It is what makes the book's evaluators -- metacircular, lazy, `amb`, query, the
explicit-control machine -- usable at all: each is a `read`-`eval`-`print` loop over stdin.

Honest priority: NO corpus file that uses `read` is complete by itself (every one is a
`fragment` in `.todo/828`'s table, missing `setup-environment` or the like), so this item
moves no number in the first-stage table. It gates the second stage -- feeding the
`embedded-*` samples (83 files) to the evaluator the corpus ships -- and any reader who
assembles a chapter 4 evaluator from the book.

- **Not the emitted Common Lisp reader** (`.kb/read-load-streams.md`): that one upcases and
  knows `#'`, `|...|`, packages. A Scheme datum must come back as what QUOTED data lowers
  to, or `(eq? (read) 'quit)` is false: identifiers verbatim but escaped by the
  `SchemeNames.mangle` rule (`%scheme-string->symbol` already spells it at run time), `#t`
  -> `T`, `#f` -> the false symbol, `()` -> `NIL`, strings, characters, numbers (the
  `%scheme-string->number` path), `'` `` ` `` `,` `,@` as `quote` / `quasiquote` / ...
  lists, `#( )`, dotted pairs, `;` `#;` `#| |#` comments.
- So: a reader in `scheme.lisp` over `read-char` / `peek-char` on `*standard-input*`,
  spliced like the rest of the run-time half, on all four backends. Check first what
  `read-char` from stdin costs and supports on wasm preview 1 and the component
  (`.kb/character-sequence-io.md`, `.kb/read-load-streams.md`, "stdin").
- `eof-object`, `eof-object?`; `(read)` at end of input answers the eof object -- a
  `driver-loop` then ends in the evaluator's own "Unknown expression type", which is the
  corpus's behaviour, not a bug.
- `read-line`, `read-char`, `peek-char`, `char-ready?` on the same port come almost free;
  string ports and `(read port)` stay with `.todo/826`.
- In a REPL session `(read)` consumes the session's own stdin: the next datum typed is the
  answer. That is the expected behaviour (`(driver-loop)` typed at the prompt takes over),
  but `SchemeSession`'s line buffer and the run-time reader must not both hold look-ahead.

Cases: `scheme-spec.yaml` needs a stdin per case (check the driver); an E2E that pipes
three expressions into a 30-line evaluator's `driver-loop`.
