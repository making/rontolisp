# print-str

`(print-str x...)`

Answers, as a string, what `print` writes for the same arguments: each spelled plain (strings bare), joined by a single space. No arguments answer `""`. The arguments are evaluated first, so what one of them prints goes to the real output, not into the result.

```clojure
(prn (print-str 1 "a" [:b "c"])) ; "1 a [:b c]"
```
