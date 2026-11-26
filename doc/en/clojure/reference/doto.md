# doto

`(doto expr step...)`

Threads `expr` as the FIRST argument through every step around one temporary, then
answers its (unchanged) target -- the steps run for effect on the value, and a list
step takes the target in head position of its insertion like `->`.

```clojure
(println (doto 5 (inc) (dec))) ; 5
```
