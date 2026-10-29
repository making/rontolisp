# ns-imports

`(ns-imports ns)`

The imported classes of the namespace `ns` (a symbol, a string or `*ns*`) -- the default
`java.lang` ones with the explicit imports over them -- each simple name to a symbol of its
class, like the oracle's map with classes (which exist only on the host backends). Only
what the lowering knows maps, and anything computed answers when the call runs on the interpreter, which keeps the program's lowering, and refuses at run time in a compiled program. As a value one
argument.

```clojure
(println (get (ns-imports 'user) 'String)) ; java.lang.String
```
