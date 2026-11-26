# prn-str

`(prn-str x...)`

Answers, as a string, what `prn` writes for the same arguments: each spelled readably (strings quoted), joined by a single space, with a trailing newline. No arguments answer `"\n"`. The arguments are evaluated first, so what one of them prints goes to the real output, not into the result.

```clojure
(prn (prn-str 1 "a" [:b "c"])) ; "1 \"a\" [:b \"c\"]\n"
```
