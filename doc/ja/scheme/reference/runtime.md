# runtime

`(runtime)`

経過した実時間を秒単位の非正確数で返します。2 回の読み取りの差を取って計算時間を測るためのもので、意味があるのは差だけです。R7RS ではなく *[Structure and Interpretation of Computer Programs](../sicp.md)*（SICP）/MIT の名前です。どのライブラリもエクスポートしないため、`import` のないファイルと REPL でだけ見えます。

```scheme
(real? (runtime)) ; => #t
(exact? (runtime)) ; => #f
```
