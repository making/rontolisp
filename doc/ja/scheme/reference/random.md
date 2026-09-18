# random

`(random n)`

0 以上 `n` 未満の擬似乱数を返します。`n` が正の正確な整数なら正確な整数を、`n` が非正確数なら非正確数を返します。`n` は正でなければならず、`(random 0)` はエラーです。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(random 1) ; => 0
(let ((n (random 6))) (and (>= n 0) (< n 6))) ; => #t
```
