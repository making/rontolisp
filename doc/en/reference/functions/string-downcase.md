# string-downcase

`(string-downcase string-designator &key (start 0) end)`

Returns a new string with every uppercase letter converted to lowercase; the original string is unchanged. The argument is a [string designator](string.md), so a symbol, a keyword or a character is also accepted -- a symbol's name is used and a keyword's leading colon is dropped, so `(string-downcase :FOO)` returns `"foo"` and `(string-downcase #\A)` returns `"a"`. Anything that is not one of those three types is an error. Case conversion is full-Unicode and identical on every backend: each character is folded with `char-downcase`, so `(string-downcase "ÉΛΩ")` returns `"éλω"`. Because the fold is per character, the result always has the same length as the argument and no context-sensitive rule applies (a Greek final sigma is not special-cased).

```lisp
(string-downcase "ABC") ; => "abc"
```

`:start` / `:end` bound the part that is converted; the characters outside it are kept as they are, and a nil `:end` means the end of the string. A range outside the string (`:start` below 0, `:end` past the length, or `:start` after `:end`) or a bound that is not an integer -- a nil `:start` included -- is the same `type-error` as [`subseq`](subseq.md)'s, on every backend.

```lisp
(string-downcase "HELLO" :end 2) ; => "heLLO"
```
