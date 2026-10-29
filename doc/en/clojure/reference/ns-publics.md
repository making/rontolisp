# ns-publics

`(ns-publics ns)`

The public interns of the namespace `ns` (a symbol, a string or `*ns*`), each name to its
var, like the oracle's. Only what the lowering knows maps: a `clojure.core` var the subset
lacks stays out (as with `resolve`), and anything computed answers when the call runs on the interpreter, which keeps the program's lowering, and refuses at run time in a compiled program. As a value
one argument.

```clojure
(def doc-eg-p 1)
(println (get (ns-publics 'user) 'doc-eg-p)) ; #'user/doc-eg-p
```
