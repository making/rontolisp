# char-escape-string

`(char-escape-string ch)`

The escape of the character `ch` (`\t`, `\r`, `\n`, `\f`, `\b`, `\\`, `\"`), `nil` for any
other character and for anything else, like the oracle's. As a value one argument.

```clojure
(prn (char-escape-string \newline) (char-escape-string \a)) ; "\\n" nil
```
