# StackTraceElement->vec

`(StackTraceElement->vec element)`

ホストのスタックトレース要素に対するオラクル通りの `[class method file line]` ベクターを
返します：クラスとメソッドはシンボル、ファイルは記述通り（要素が名前を持たない場合は `nil`）、
行も記述通りです。`nil` はオラクルの `NullPointerException`、それ以外は `ClassCastException`
になります。引数がない場合はオラクルの個数拒否です。関数値としても使えます。要素は
インタプリタと JVM にのみ存在する（`.getStackTrace` は `[]` を返す）ため、wasm バックエンド
では 2 つの拒否だけが実行可能です（[Deviations](../deviations.md) を見てください）。

```clojure
(prn (StackTraceElement->vec (StackTraceElement. "C" "m" "F.clj" 42))) ; [C m "F.clj" 42]
```
