# string->number

`(string->number string)` `(string->number string radix)`

Parses `string` as a number in `radix` (default 10) and returns it, or `#f` when the text is not a number. Integers, ratios and decimals with an optional sign and exponent are read, and `+inf.0` `-inf.0` `+nan.0` `-nan.0` in any case. `string` may itself start with an R7RS prefix -- a radix (`#x`, `#b`, `#o`, `#d`) and an exactness (`#e`, `#i`), each at most once, in either order -- which then wins over `radix`; `#e` on a decimal answers the exact rational the digits spell, never rounded through a flonum, and `#i` converts an exact answer to a flonum. A repeated or unrecognized prefix answers `#f`.

```scheme
(string->number "ff" 16) ; => 255
(string->number "#xff") ; => 255
(string->number "#e1.5") ; => 3/2
(string->number "#i5") ; => 5.0
(string->number "1/2") ; => 1/2
(string->number "abc") ; => #f
(string->number "-inf.0") ; => -inf.0
```
