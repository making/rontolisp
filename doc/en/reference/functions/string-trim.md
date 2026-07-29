# string-trim

`(string-trim character-bag string)`

Returns a new string with all leading and trailing characters that appear in `character-bag` removed; interior characters are untouched. `character-bag` is a string OR a list of characters whose members form the set to strip. Trimming stops at the first character on each end that is not in the bag.

```lisp
(string-trim " " "  hi  ") ; => "hi"
```

```lisp
(string-trim (list #\Space #\Tab) "  hi  ") ; => "hi"
```