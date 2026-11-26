# interaction-environment

`(interaction-environment)`

実行中のプログラムの環境指定子を `eval` 用に返します。これはすべての指定子が指す唯一の大域環境で、`#[environment]` と表示されます。プログラムの変数と手続き、および組み込み手続きを含みます。

```scheme
(interaction-environment) ; => #[environment]
(eval '(+ 1 2) (interaction-environment)) ; => 3
```
