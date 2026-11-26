# ns-aliases

`(ns-aliases ns)`

The aliases of the namespace `ns` (a symbol, a string or `*ns*`), each alias to its
namespace object, like the oracle's. Only what the lowering knows maps, and anything
computed answers when the call runs on the interpreter, which keeps the program's lowering, and refuses at run time in a compiled program. As a value one argument.

```clojure
(alias 'doc-eg-s 'clojure.string)
(println (ns-name (get (ns-aliases 'user) 'doc-eg-s))) ; clojure.string
```
