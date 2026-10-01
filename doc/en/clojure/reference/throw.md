# throw

`(throw ex)`

Signals `ex`. An `ex-info` value signals as its own condition, so `catch` sees it and `ex-data`
answers its map; anything else signals through its Clojure-notation rendering -- a thrown string
keeps the string as its message, a thrown map prints as the map.

```clojure
(println (try (throw (ex-info "boom" {:code 42})) (catch Exception e (get (ex-data e) :code)))) ; 42
(println (try (throw "plain") (catch Exception e (str "got-" e)))) ; got-plain
```
