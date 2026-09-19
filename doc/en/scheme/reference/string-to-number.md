# string->number

`(string->number string)` `(string->number string radix)`

Parses `string` as a number in `radix` (default 10) and returns it, or `#f` when the text is not a number. Integers, ratios and decimals with an optional sign and exponent are read, and `+inf.0` `-inf.0` `+nan.0` `-nan.0` in any case; the radix prefixes (`#x`, `#b`, ...) and exactness prefixes are not, and answer `#f`.

```scheme
(string->number "ff" 16) ; => 255
(string->number "1/2") ; => 1/2
(string->number "abc") ; => #f
(string->number "-inf.0") ; => -inf.0
```
