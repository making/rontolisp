# class

`(class x)`

Answers the value's kind as a keyword: `:map`, `:vector`, `:set`, `:list`,
`:string`, `:number`, `:keyword`, `:symbol`, `:char`, `:boolean`, `:nil`,
`:function` or `:atom`. The oracle answers host classes, which no wasm backend
has -- the keyword names the kind instead, on every backend alike. As a value a
one-argument lambda.

```clojure
(println (class "a") (class 1)) ; :string :number
```
