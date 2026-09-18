# eval

`(eval expr)` `(eval expr environment)`

データ `expr` を実行時に評価して値を返します。環境指定子（`(interaction-environment)`、`(environment ...)`、`(scheme-report-environment 5)`、`user-initial-environment`、`system-global-environment`）はすべて唯一の大域環境を指し、引数は省略できます（R7RS では必須です）。`eval` の中の `define` はプログラムの大域変数を作ります。`eval` の中では `define-record-type`、`define-values`、`let-values`、`import` は拒否されます。詳しい意味は [eval](../eval.md) を参照してください。

```scheme
(eval '(+ 1 2) (interaction-environment)) ; => 3
(eval '(* 2 3)) ; => 6
```
