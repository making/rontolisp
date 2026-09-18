# cond

`(cond (test expression...)... [(else expression...)])` `(cond (test => receiver)...)`

`test` を順に評価し、最初に真になった節の式を評価して最後の値を返します。式のない節は test の値を返します。`(test => receiver)` 節では test の値を引数に `receiver` を呼びます。`else` 節は、それより前のどの節にも当てはまらなかったときに選ばれます。どの節も選ばれなければ値は未規定値です。

```scheme
(cond ((> 1 2) 'a) ((> 2 1) 'b) (else 'c)) ; => b
(cond ((assv 'b '((a 1) (b 2))) => cadr) (else #f)) ; => 2
(cond ((+ 1 1))) ; => 2
```
