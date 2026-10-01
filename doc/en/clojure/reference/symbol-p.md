# symbol?

`(symbol? x)`

`true` for an identifier. Keywords (which are lists), the booleans and `nil`
are no symbols, like the oracle. As a value a one-argument lambda answering
`T`-or-`false`.

```clojure
(println (symbol? 'a) (symbol? :a)) ; true false
```
