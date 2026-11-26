# char-name-string

`(char-name-string ch)`

The name of the character `ch` (`space`, `tab`, `newline`, `return`, `backspace`,
`formfeed`), `nil` for any other character and for anything else, like the oracle's. As a
value one argument.

```clojure
(prn (char-name-string \newline) (char-name-string \a)) ; "newline" nil
```
