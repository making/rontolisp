# string-upcase

`(string-upcase string-designator)`

Returns a new string with every lowercase letter converted to uppercase; the original string is unchanged. The argument is a string designator, so a symbol or keyword is also accepted -- its name is used and a keyword's leading colon is dropped, so `(string-upcase :foo)` returns `"FOO"`. In the WASM backend case conversion is ASCII-only, so only the letters `a`-`z` are affected and non-ASCII characters pass through untouched.

```lisp
(string-upcase "abc") ; => "ABC"
```
