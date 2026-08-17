# ctypecase

`(ctypecase x (integer body...) (string body...))`

`typecase` の網羅的な変種です。同じ型指定子の集合に対して `x` の型でディスパッチしますが、デフォルト節を持ちません。`x` がどの節にもマッチしない場合、`ctypecase` は nil を返す代わりに `error` をシグナルします。これにより、想定されるすべての型を明示的に扱うべき場合に適した選択肢となります。完全な Common Lisp では `ctypecase` は *修正可能 (correctable)* で、新しい値を供給するための `store-value` リスタートを提供しますが、rontolisp の `ctypecase` は `store-value` リスタートを確立しないため `etypecase` とまったく同じように動作し、主にソースの互換性のために提供されています。

```lisp
(let ((x 42)) (ctypecase x (integer 'int) (string 'str))) ; => INT
```
