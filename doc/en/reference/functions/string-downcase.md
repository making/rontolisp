# string-downcase

`(string-downcase string-designator)`

Returns a new string with every uppercase letter converted to lowercase; the original string is unchanged. The argument is a string designator, so a symbol or keyword is also accepted -- its name is used and a keyword's leading colon is dropped, so `(string-downcase :FOO)` returns `"foo"`. Case conversion is full-Unicode and identical on every backend: each character is folded with `char-downcase`, so `(string-downcase "ÉΛΩ")` returns `"éλω"`. Because the fold is per character, the result always has the same length as the argument and no context-sensitive rule applies (a Greek final sigma is not special-cased).

```lisp
(string-downcase "ABC") ; => "abc"
```
