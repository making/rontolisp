# special-symbol?

`(special-symbol? x)`

`clojure.core/special-symbol?`: `true` for a symbol naming one of the oracle's special forms (`if`, `def`, `let*`, `fn*`, `quote`, `var`, `recur`, `try`, ...); the macros over them (`let`, `fn`, `loop`) are `false`. As a value a one-argument function.

```clojure
(println (special-symbol? 'if) (special-symbol? 'let))  ; true false
```
