# Syntax

- **Reader** (case-sensitive): `#t` `#f` `#true` `#false`, integers, decimals, rationals,
  `#x` `#b` `#o` `#d`, `#\a` `#\space` `#\newline` `#\x41`, strings with
  `\n \t \" \\ \xHH;`, `#( )` vectors, dotted pairs, `'` `` ` `` `,` `,@`, `;`, `#;`,
  `#| |#`, `#!fold-case` / `#!no-fold-case` (per file, folding identifiers and character
  names -- not strings, not the character itself -- until the counterpart directive or
  end of file).
- **Special forms**: one page each under [Syntax](reference/syntax.md) in the
  reference (`delay` and `delay-force` under [(scheme lazy)](reference/library-lazy.md)),
  and
  `(import (scheme base) (scheme write) (scheme read) (scheme inexact) (scheme cxr) (scheme lazy)
  (scheme process-context) (scheme eval) (scheme repl))` with `only` / `except` /
  `prefix` / `rename`.

See [Libraries](libraries.md) for what each of those nine libraries exports, and
[*Structure and Interpretation of Computer Programs* (SICP) Compatibility](sicp.md) for the
names visible with no `import` at all.
