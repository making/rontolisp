# Scheme `string->number` with radix and exactness prefixes

Difficulty: Low

Found by `.todo/886` (2026-09-19). R7RS 6.2.7: `string->number` reads any `<number>`,
prefixes included. Here `(string->number "#xff")` and `(string->number "#b101")` answer
`#f` (Gauche: `255`, `5`), as does `(string->number "#x+inf.0")`; `#e`/`#i` are read
nowhere, source included. The reference page `string->number` states the gap.

## Scope

- `%scheme-string->number` (`scheme.lisp`) takes `#x` `#b` `#o` `#d` (the run-time reader
  already parses them in `%scheme-hash-token-datum`; share it) and, with them or alone,
  `#e` / `#i` -- also in `SchemeReader` for source.
- A program that does not call `string->number` keeps its bytes (measure).

## Test plan

- `scheme-spec.yaml` case against `gosh -r7`; the reference pages (doc/en + doc/ja).
