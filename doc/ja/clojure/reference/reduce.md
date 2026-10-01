# reduce

`(reduce f coll)` / `(reduce f val coll)`

`coll` の seq ビューへ `f` を左から右へ畳み込みます。3 アリティは Clojure の引数順 -- 関数、初期値、コレクション -- を取り、基盤の `reduce` の `:initial-value` へ写像されます。2 アリティは種なしで畳み込みます。

```clojure
(println (reduce + '(1 2 3)))  ; 6
(println (reduce + 0 [1 2 3])) ; 6
```
