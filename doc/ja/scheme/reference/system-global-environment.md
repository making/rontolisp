# system-global-environment

`system-global-environment`

MIT Scheme でのグローバル環境のもう 1 つの名前を保持する変数で、`eval` の第 2 引数に使います。`user-initial-environment` と同じオブジェクトで、`#[environment]` と書き出されます。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(eval '(* 2 3) system-global-environment) ; => 6
(eq? system-global-environment user-initial-environment) ; => #t
```
