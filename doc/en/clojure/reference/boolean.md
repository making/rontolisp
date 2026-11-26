# boolean

`(boolean x)`

`true` for anything truthy; only `nil` and `false` are falsey (`0`, empty strings
and empty collections count as `true`). As a value a one-argument lambda.

```clojure
(println (boolean 1)) ; true
(println (boolean nil)) ; false
```
