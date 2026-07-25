# logbitp

`(logbitp index integer)`

2 の補数表現の `integer` の第 `index` ビット（0 = 最下位ビット）を調べ、立っていれば `t`、そうでなければ `nil` を返します。負の `integer` には上位に無限個の 1 ビットがあるため、`(logbitp index -1)` はどの `index` でも `t` です。インタプリタと JVM では `integer` は任意の大きさを取れますが、WASM ではビットの読み取りは符号付き 64 ビットの範囲で行われます（63 を超える index は符号ビットを読みます）。

```lisp
(logbitp 2 5) ; => T
```

```lisp
(logbitp 1 5) ; => NIL
```
