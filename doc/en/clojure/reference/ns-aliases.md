# ns-aliases

`(ns-aliases ns)`

The aliases of the namespace `ns` (a symbol, a string or `*ns*`), each alias to its
namespace object, like the oracle's. Only what the lowering knows maps, and anything
computed refuses at run time. As a value one argument.

```clojure
(alias 'doc-eg-s 'clojure.string)
(println (ns-name (get (ns-aliases 'user) 'doc-eg-s))) ; clojure.string
```
