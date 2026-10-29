# remove-tap

`(remove-tap f)`

`clojure.core/remove-tap`: removes `f` from the taps [tap>](tap-gt.md) calls, answering `nil`; a value tapped afterwards no longer reaches it. The tap is found by `identical?`. As a value a one-argument function.

```clojure
(add-tap println)
(println (remove-tap println)) ; nil
```
