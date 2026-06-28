# string-trim

`(string-trim character-bag string)`

Returns a new string with all leading and trailing characters that appear in `character-bag` removed; interior characters are untouched. `character-bag` is itself a string whose characters form the set to strip. Trimming stops at the first character on each end that is not in the bag.

```lisp
(string-trim " " "  hi  ") ; => "hi"
```
