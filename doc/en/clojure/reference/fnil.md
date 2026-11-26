# fnil

`(fnil f x)` / `(fnil f x y)` / `(fnil f x y z)`

Answers `f` behind a patch replacing a `nil` first (second, third) argument with `x`
(`y`, `z`); `false` is not patched. The answer needs at least as many arguments as
defaults, like the oracle's. As a value it takes a function and one to three
defaults.

```clojure
(println ((fnil inc 0) nil)) ; 1
(println ((fnil + 1 2) nil nil 3)) ; 6
```
