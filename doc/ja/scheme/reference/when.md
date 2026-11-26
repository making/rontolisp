# when

`(when test expression...)`

`test` が真なら式を評価して最後の値を返します。そうでなければ値は未規定値です。式は 1 つ以上必要です。

```scheme
(when (> 2 1) 'a 'b) ; => b
(list (when (> 1 2) 'a)) ; => (#!unspecific)
```
