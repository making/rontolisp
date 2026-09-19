# string-foldcase

`(string-foldcase string)`

Returns a new string: `string` by Unicode full case folding, which is what `string-ci=?` and the other `-ci` string comparisons compare and what `#!fold-case` applies to identifiers. Unicode, not Gauche: `ẞ` folds to `ss` and `ı` does not fold (Gauche: `ß`, `i`).

```scheme
(string-foldcase "Straße") ; => "strasse"
(string-foldcase "ΧΑΟΣ") ; => "χαοσ"
```
