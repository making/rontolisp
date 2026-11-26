# requiring-resolve

`(requiring-resolve sym)`

The namespace of the qualified symbol `sym` loaded and its var answered -- `nil` for a
name that names nothing -- like the oracle's. A quoted symbol resolves while the program
lowers; anything computed answers when the call runs on the interpreter, which keeps the program's lowering, and refuses at run time in a compiled program. As a value
one argument.

```clojure
(println (requiring-resolve 'clojure.string/join)) ; #'clojure.string/join
```
