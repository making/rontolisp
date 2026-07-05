# integer-length

`(integer-length integer)`

`integer` の 2 の補数表現の絶対値を符号を除いて表すのに必要なビット数を返します。非負の `integer` では最上位の立っているビットの位置に 1 を加えた値であり、負の `integer` ではその 1 の補数の長さになります。したがって `(integer-length -1)` は `0`、`(integer-length -5)` は `3` です。インタプリタと JVM では `integer` は任意の大きさを取れますが、WASM では 31 ビットの `i31` です。

```lisp
(integer-length 255) ; => 8
```

```lisp
(integer-length -5) ; => 3
```
