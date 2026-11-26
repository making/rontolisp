# list->string

`(list->string list)`

Returns a new string made of the characters in `list`. An element that is not a character signals an error.

```scheme
(list->string '(#\a #\b)) ; => "ab"
(list->string '()) ; => ""
```
