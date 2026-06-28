# string-downcase

`(string-downcase string)`

Returns a new string with every uppercase letter converted to lowercase; the original string is unchanged. In the WASM backend case conversion is ASCII-only, so only the letters `A`-`Z` are affected and non-ASCII characters pass through untouched.

```lisp
(string-downcase "ABC") ; => "abc"
```
