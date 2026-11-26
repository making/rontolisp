# distinct?

`(distinct? x & more)`

`clojure.core/distinct?`: `true` when no two arguments are `=`, collections compared by contents. As a value a function of one or more arguments.

```clojure
(println (distinct? 1 2 3) (distinct? 1 2 1) (distinct? [1] '(1)))  ; true false false
```
