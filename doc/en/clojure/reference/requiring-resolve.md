# requiring-resolve

`(requiring-resolve sym)`

The namespace of the qualified symbol `sym` loaded and its var answered -- `nil` for a
name that names nothing -- like the oracle's. Only a quoted symbol resolves; anything
computed refuses at run time. As a value one argument (which refuses).

```clojure
(println (requiring-resolve 'clojure.string/join)) ; #'clojure.string/join
```
