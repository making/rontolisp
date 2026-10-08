# long

`(long x)`

オラクルの `long` 型変換です。数は0方向へ切り捨て、文字はそのコードを返します。long の範囲外の
値は `Value out of range for long: ...` をシグナルします。NaN は `0`、ちょうど 2^63 の double は
long の最大値で、非数はシグナルします。値としては1引数の関数です。

```clojure
(println (long -2.7)) ; -2
```
