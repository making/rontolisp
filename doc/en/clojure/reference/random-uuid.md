# random-uuid

`(random-uuid)`

`clojure.core/random-uuid`: a version 4 UUID of 122 random bits from
`rontolisp:random-bytes`, the cryptographic source (the oracle's is `SecureRandom`). Runs on
every backend; as a value, no arguments.

```clojure
(def id (random-uuid))
(println (uuid? id) (.version id) (.variant id) (count (str id)) (= id (parse-uuid (str id))))
```

```
true 4 2 36 true
```
