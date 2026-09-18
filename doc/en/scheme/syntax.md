# Syntax

- **Reader** (case-sensitive): `#t` `#f` `#true` `#false`, integers, decimals, rationals,
  `#x` `#b` `#o` `#d`, `#\a` `#\space` `#\newline` `#\x41`, strings with
  `\n \t \" \\ \xHH;`, `#( )` vectors, dotted pairs, `'` `` ` `` `,` `,@`, `;`, `#;`,
  `#| |#`.
- **Special forms**: `define` (both forms, internal definitions as `letrec*`),
  `define-values`,
  `lambda`, `if`, `cond` (`else`, `=>`), `case`, `and`, `or`, `when`, `unless`, `let`,
  `let*`, `letrec`, `letrec*`, named `let`, `do`, `begin`, `set!`, `quote`, `quasiquote`,
  `let-values`, `let*-values`, `define-record-type` (top level only), `delay`,
  `delay-force`, and
  `(import (scheme base) (scheme write) (scheme read) (scheme inexact) (scheme cxr) (scheme lazy)
  (scheme process-context) (scheme eval) (scheme repl))` with `only` / `except` /
  `prefix` / `rename`.

See [Libraries](libraries.md) for what each of those nine libraries exports, and
[SICP Compatibility](sicp.md) for the names visible with no `import` at all.
