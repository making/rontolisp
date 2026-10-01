# declare

`(declare name...)`

Forward-declares each name: the pass one pre-scan collects every `declare` name
alongside the top-level `def`/`defn` names, so a definition above may call a function
only defined below it. A real definition wins over a declaration, and a
`declare`d-but-never-defined name keeps its direct-call error. The form itself
answers `nil`.

```clojure
(declare dcl-f)
(defn dcl-g [] (dcl-f 1))
(defn dcl-f [x] (* 2 x))
(println (dcl-g)) ; 2
```
