# string-downcase

`(string-downcase string-designator)`

Returns a new string with every uppercase letter converted to lowercase; the original string is unchanged. The argument is a string designator, so a symbol or keyword is also accepted -- its name is used and a keyword's leading colon is dropped, so `(string-downcase :FOO)` returns `"foo"`. In the WASM backend case conversion is ASCII-only, so only the letters `A`-`Z` are affected and non-ASCII characters pass through untouched.

```lisp
(string-downcase "ABC") ; => "abc"
```
