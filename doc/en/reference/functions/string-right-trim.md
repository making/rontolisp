# string-right-trim

`(string-right-trim character-bag string)`

Returns a new string with trailing characters that appear in `character-bag` removed from the end only; the left end is left intact. `character-bag` is any sequence of characters -- a string, a list, or a vector -- whose members form the set to strip, and trimming stops at the last character not in the bag. `string` is a [string designator](string.md), so `(string-right-trim "O" '|FOO|)` returns `"F"`.

```lisp
(string-right-trim "x" "hixx") ; => "hi"
```
