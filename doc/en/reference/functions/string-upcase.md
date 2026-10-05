# string-upcase

`(string-upcase string-designator &key (start 0) end)`

Returns a new string with every lowercase letter converted to uppercase; the original string is unchanged. The argument is a [string designator](string.md), so a symbol, a keyword or a character is also accepted -- a symbol's name is used and a keyword's leading colon is dropped, so `(string-upcase :foo)` returns `"FOO"` and `(string-upcase #\a)` returns `"A"`. Anything that is not one of those three types is an error. Case conversion is full-Unicode and identical on every backend: each character is folded with `char-upcase`, so `(string-upcase "éλω")` returns `"ÉΛΩ"`. Because the fold is per character, the result always has the same length as the argument -- there is no multi-character special casing (`(string-upcase "straße")` returns `"STRAßE"`, not `"STRASSE"`).

```lisp
(string-upcase "abc") ; => "ABC"
```

`:start` / `:end` bound the part that is converted; the characters outside it are kept as they are, and a nil `:end` means the end of the string. A range outside the string (`:start` below 0, `:end` past the length, or `:start` after `:end`) is the same `type-error` as [`subseq`](subseq.md)'s, on every backend.

```lisp
(string-upcase "hello" :start 1 :end 3) ; => "hELlo"
```
