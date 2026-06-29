# unless

`(unless condition body...)`

`when` の補集合です。`condition` を評価し、それが nil のときに限って本体のフォームを順に評価し、最後のものの値を返します。`condition` が真の場合は本体はスキップされ、`unless` は nil を返します。then／else の役割を反転させた `if` に展開されます。

```lisp
(unless (> 3 5) 'yes) ; => yes
```
