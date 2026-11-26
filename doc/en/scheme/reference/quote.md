# quote

`(quote datum)` `'datum`

Answers `datum` itself, unevaluated. `'datum` is the reader's abbreviation for `(quote datum)`. `write` and the REPL print a quoted datum in its long form: `''x` shows as `(quote x)`, not `'x`.

```scheme
(quote (a b c)) ; => (a b c)
'sym ; => sym
''x ; => (quote x)
```
