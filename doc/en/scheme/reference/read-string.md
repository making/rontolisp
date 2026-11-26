# read-string

`(read-string k [port])`

Reads at most `k` characters from the textual input port `port` (the current input port by default) and returns them as a string; fewer at the end of input, and the end-of-file object when none is left. `(read-string 0)` returns `""`.

```scheme
(read-string 3 (open-input-string "abcdef")) ; => "abc"
```
