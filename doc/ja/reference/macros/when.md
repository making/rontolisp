# when

`(when condition body...)`

`condition` を評価し、それが真の場合は本体のフォームを順に評価して、最後のものの値を返します。`condition` が nil の場合は本体はスキップされ、`when` は nil を返します。else 分岐を持たず、本体が暗黙の `progn` である `if` の省略形です。

```lisp
(when (> 5 3) 'yes) ; => YES
```
