# find-keyword

`(find-keyword x)` / `(find-keyword ns nm)`

`clojure.core/find-keyword`: the keyword for a keyword, a symbol's spelling or a string, `nil`
for anything else; with two arguments the keyword `ns/nm` (a `nil` namespace drops; a `nil`
name or a non-string part signals). Keywords are not interned here, so a spelling no
keyword ever used answers its keyword where the oracle answers `nil`. As a value one or two
arguments.

```clojure
(println (find-keyword "a"))       ; :a
(println (find-keyword "b" "c"))   ; :b/c
(println (find-keyword 1))         ; nil
```
