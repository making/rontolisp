# println-str

`(println-str x...)`

Answers, as a string, what `println` writes for the same arguments: each spelled plain (strings bare), joined by a single space, with a trailing newline. No arguments answer `"\n"`. The arguments are evaluated first, so what one of them prints goes to the real output, not into the result.

```clojure
(prn (println-str 1 "a" [:b "c"])) ; "1 a [:b c]\n"
```
