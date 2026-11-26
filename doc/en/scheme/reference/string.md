# string

`(string char ...)`

Returns a new string made of the given characters. With no arguments it returns the empty string. An argument that is not a character signals an error.

```scheme
(string #\a #\b) ; => "ab"
(string) ; => ""
```
