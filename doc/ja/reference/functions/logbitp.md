# logbitp

`(logbitp index integer)`

2 の補数表現の `integer` の第 `index` ビット（0 = 最下位ビット）を調べ、立っていれば `t`、そうでなければ `nil` を返します。負の `integer` には上位に無限個の 1 ビットがあるため、`(logbitp index -1)` はどの `index` でも `t` です。インタプリタと JVM では `integer` は任意の大きさを取れますが、WASM ではビットの読み取りは 31 ビットの `i31` の範囲に限られます。

```lisp
(logbitp 2 5) ; => t
```

```lisp
(logbitp 1 5) ; => nil
```
