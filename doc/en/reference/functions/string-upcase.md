# string-upcase

`(string-upcase string-designator)`

Returns a new string with every lowercase letter converted to uppercase; the original string is unchanged. The argument is a string designator, so a symbol or keyword is also accepted -- its name is used and a keyword's leading colon is dropped, so `(string-upcase :foo)` returns `"FOO"`. Case conversion is full-Unicode and identical on every backend: each character is folded with `char-upcase`, so `(string-upcase "éλω")` returns `"ÉΛΩ"`. Because the fold is per character, the result always has the same length as the argument -- there is no multi-character special casing (`(string-upcase "straße")` returns `"STRAßE"`, not `"STRASSE"`).

```lisp
(string-upcase "abc") ; => "ABC"
```
