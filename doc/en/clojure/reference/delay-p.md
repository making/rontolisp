# delay?

`(delay? x)`

`clojure.core/delay?`: `true` for a [delay](delay.md), `false` for any other value. The argument is still evaluated. As a value a one-argument function.

```clojure
(println (delay? 1) (delay? (delay 1)))  ; false true
```
