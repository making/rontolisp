# do

`(do expr...)`

Evaluates the forms in order and answers the last value; with no form, `nil`. The body of
`defn`/`fn`/`let`/`loop`/`when` is an implicit `do` over its forms.

```clojure
(println (do 1 2 3)) ; 3
```
