# hash-combine

`(hash-combine x y)`

`clojure.core/hash-combine`: boost's combination of the int `x` with `y`'s Java `hashCode`
(`Util.hash`: `0` for `nil`, a collection's `List`, `Map` or `Set` hash, a keyword's
`Keyword.hashCode`), `x xor (hashCode + 0x9e3779b9 + (x << 6) + (x >> 2))` in 32 bits. `x`
is cast like an int parameter: a double or a ratio truncates, a value past the int range is
the oracle's `ArithmeticException`, and a character or `nil` is refused.

```clojure
(prn (hash-combine 0 :a))   ; -626620958
(prn (hash-combine 1 2))    ; -1640531462
```
