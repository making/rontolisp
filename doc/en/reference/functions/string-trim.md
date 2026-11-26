# string-trim

`(string-trim character-bag string)`

Returns a new string with all leading and trailing characters that appear in `character-bag` removed; interior characters are untouched. `character-bag` is any sequence of characters -- a string, a list, or a vector -- whose members form the set to strip. Trimming stops at the first character on each end that is not in the bag.

`string` is a [string designator](string.md), so a symbol or a character is accepted and its name is trimmed: `(string-trim "*" '*foo*)` returns `"FOO"`. `character-bag` is NOT a designator -- it is a sequence -- so a lone character there is an error, not a one-character bag.

```lisp
(string-trim " " "  hi  ") ; => "hi"
```

```lisp
(string-trim (list #\Space #\Tab) "  hi  ") ; => "hi"
```