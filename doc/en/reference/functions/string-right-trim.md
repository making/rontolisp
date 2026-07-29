# string-right-trim

`(string-right-trim character-bag string)`

Returns a new string with trailing characters that appear in `character-bag` removed from the end only; the left end is left intact. `character-bag` is a string OR a list of characters whose members form the set to strip, and trimming stops at the last character not in the bag.

```lisp
(string-right-trim "x" "hixx") ; => "hi"
```
