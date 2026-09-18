# scheme-report-environment

`(scheme-report-environment version)`

`eval` 用の環境を返します。環境指定子はすべて 1 つの大域環境 `#[environment]` を表し、`version` の値は問いません。`(scheme r5rs)` に属し、`import` のないファイルからだけ見えます。[eval](../eval.md) を参照してください。

```scheme
(scheme-report-environment 5) ; => #[environment]
(eval '(* 6 7) (scheme-report-environment 5)) ; => 42
```
