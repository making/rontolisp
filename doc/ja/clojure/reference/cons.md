# cons

`(cons x coll)`

`coll` の seq ビューの先頭へ `x` を付したリストを返します。ビューは実体化されるため、どのコレクションでも動きます。`nil` への `cons` はリストを組み立てます。

```clojure
(println (cons 1 [2 3])) ; (1 2 3)
(println (cons 0 '(1 2))) ; (0 1 2)
(println (cons :a nil))  ; (:a)
```
