# string-downcase

`(string-downcase string)`

Returns a new string: `string` by the Unicode full lowercase mapping. A capital sigma at the end of a word becomes the final sigma `ς`; `İ` becomes `i` followed by a combining dot above.

```scheme
(string-downcase "HELLO") ; => "hello"
(string-downcase "ΧΑΟΣ") ; => "χαος"
```
