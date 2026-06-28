# string-left-trim

`(string-left-trim character-bag string)`

Returns a new string with leading characters that appear in `character-bag` removed from the front only; the right end is left intact. `character-bag` is a string whose characters form the set to strip, and trimming stops at the first character not in the bag.

```lisp
(string-left-trim "x" "xxhi") ; => "hi"
```
