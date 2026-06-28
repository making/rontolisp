# string-upcase

`(string-upcase string)`

Returns a new string with every lowercase letter converted to uppercase; the original string is unchanged. In the WASM backend case conversion is ASCII-only, so only the letters `a`-`z` are affected and non-ASCII characters pass through untouched.

```lisp
(string-upcase "abc") ; => "ABC"
```
