# b73. `macroexpand`/`macroexpand-1` answer data a program can compare

Difficulty: Medium

The run-time expanders answer the expansion demangled into uppercased Common Lisp symbols
(`.kb/clojure-frontend.md`, the `macroexpand-1` row: "case folds, print-only"), so
`(macroexpand-1 '(unless false :foo))` prints `(IF false nil :foo)` and is not `=` to
`'(if false nil :foo)`. Since b56, 7 of the 27 shcloj4 test namespaces (`macros`,
`macros/chain_1..5`, `macros/bench_1`) fail on this alone: their expansions otherwise
match the oracle's, `examples.macros.chain-4/chain` qualified like it.

Idea to check first: answer the mangled data itself (`c%if`, ...). The Clojure printer
already demangles `c%` symbols (a quoted symbol prints demangled), so `=` against a quoted
form would hold and printing would spell the oracle's lowercase. Find why b12 chose
"their data takes bare operator names" before changing it.

## Acceptance

- `(= (macroexpand-1 '(m ...)) '(...))` holds where the oracle's does, and the printed
  expansion is the oracle's text, on all four backends.
- The spec cases pinning the uppercase today (`defmacro-unless-expands-before-backends`,
  `chain-macros-rebuild-dot-forms`, `macroexpand-keeps-strings-and-keywords`) move to the
  oracle's spelling; the 7 corpus namespaces are re-run.
