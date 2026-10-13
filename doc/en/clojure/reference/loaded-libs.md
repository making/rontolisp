# loaded-libs

`(loaded-libs)`

The namespaces the program loaded before the call -- the libraries it required, the project
namespaces whose files ran and the loaded startup ones -- as a set of symbols, like the
oracle's (`user` and `clojure.core` are never loaded). Order is unspecified; only membership
pins. As a value no arguments.

```clojure
(require 'clojure.string)
(println (contains? (set (map str (loaded-libs))) "clojure.string")) ; true
```
