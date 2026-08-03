# random

`(random limit &optional random-state)`

半開区間 `[0, limit)` の乱数を返します。結果の型は limit に従い、整数の limit は整数を、浮動小数点数の limit は浮動小数点数を返します（そのため `(random 1)` は常に `0` です）。インタプリタと JVM は `Math.random` を使い、WASM は WASI ホストから実際のエントロピーを取得します（Preview 1 では `random_get`、`--component` では `wasi:random`）。そのため実行のたびに列が異なります。省略可能な random-state 引数は受理された上で無視されます(副作用のために評価はされます)。random-state オブジェクトは存在せず — [`make-random-state`](make-random-state.md) は `nil` を返します — 常にバックエンド自身のエントロピーが使われます。

```lisp
(random 1) ; => 0
```
