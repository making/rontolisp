# false?

`(false? x)`

`true` for the false object alone: `nil` is a distinct object and answers `false`.

```clojure
(println (false? false)) ; true
(println (false? nil)) ; false
```
