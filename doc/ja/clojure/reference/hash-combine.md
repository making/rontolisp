# hash-combine

`(hash-combine x y)`

`clojure.core/hash-combine`: int の `x` と `y` の Java の `hashCode`（`Util.hash`。`nil` は
`0`、コレクションは `List`・`Map`・`Set` のハッシュ、キーワードは `Keyword.hashCode`）を boost の
方式で組み合わせ、32 ビットで `x xor (hashCode + 0x9e3779b9 + (x << 6) + (x >> 2))` を返します。
`x` は int 引数として変換され、double と比は切り捨て、int の範囲外はオラクルの
`ArithmeticException`、文字と `nil` は拒否されます。

```clojure
(prn (hash-combine 0 :a))   ; -626620958
(prn (hash-combine 1 2))    ; -1640531462
```
