# etypecase

`(etypecase x (integer body...) (string body...))`

`typecase` の網羅的な変種です。同じ型指定子の集合に対して `x` の型でディスパッチしますが、デフォルト節を持ちません。`x` がどの節にもマッチしない場合、`etypecase` は nil を返す代わりに `error` をシグナルします。これにより、想定されるすべての型を明示的に扱うべき場合に適した選択肢となります。

```lisp
(let ((x 42)) (etypecase x (integer 'int) (string 'str))) ; => int
```
