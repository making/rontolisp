# string-left-trim

`(string-left-trim character-bag string)`

Returns a new string with leading characters that appear in `character-bag` removed from the front only; the right end is left intact. `character-bag` is any sequence of characters -- a string, a list, or a vector -- whose members form the set to strip, and trimming stops at the first character not in the bag. `string` is a [string designator](string.md), so `(string-left-trim "F" '|FOO|)` returns `"OO"`.

```lisp
(string-left-trim "x" "xxhi") ; => "hi"
```
