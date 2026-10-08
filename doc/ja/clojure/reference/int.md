# int

`(int x)`

オラクルの `int` 型変換です。数は0方向へ切り捨て、文字はそのコードを返します。int の範囲外の
値はオラクルと同じくシグナルします（`integer overflow`。double リテラルなら
`Value out of range for int: 1.0E10`）。NaN は `0` で、非数はシグナルします。値としては
1引数の関数です。

```clojure
(println (int 2.7)) ; 2
```
