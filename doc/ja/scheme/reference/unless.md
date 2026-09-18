# unless

`(unless test expression...)`

`test` が `#f` なら式を評価して最後の値を返します。そうでなければ値は未規定値です。式は 1 つ以上必要です。

```scheme
(unless (> 1 2) 'ran) ; => ran
(list (unless #t 'ran)) ; => (#!unspecific)
```
