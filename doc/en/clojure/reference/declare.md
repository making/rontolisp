# declare

`(declare name...)`

Forward-declares each name: the pass one pre-scan collects every `declare` name
alongside the top-level `def`/`defn` names, so a definition above may call a function
only defined below it. A real definition wins over a declaration. A
`declare`d-but-never-defined name is an unbound var, like the oracle's: its value and
`@#'name` are the unbound root (truthy; `str` gives `Unbound: #'user/name`; it prints
`#<Unbound: #'user/name>`), calling it signals `Attempting to call unbound fn:
#'user/name`, and [bound?](bound-p.md) is `false`. A `declare` leaves a bound root alone
and adds `:declared true` to the var's metadata; a `^:dynamic` name rebinds with
`binding`. The form itself answers `nil`.

```clojure
(declare dcl-f)
(defn dcl-g [] (dcl-f 1))
(defn dcl-f [x] (* 2 x))
(println (dcl-g)) ; 2
(declare dcl-u)
(println (str dcl-u) (bound? #'dcl-u)) ; Unbound: #'user/dcl-u false
```
