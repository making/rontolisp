# 入出力

`eval` IO 層上のファイル入口です。インタプリタと JVM で動きます（wasm にファイルシステムは
ありません）。`format` は Clojure 記法の引数で Java 形式の文字列を描画します。

| Name | Example | Result |
|---|---|---|
| `spit` | `(spit path "a\n")` | `nil` |
| `slurp` | `(slurp path)` | `"a\n"` |
| `line-seq` | `(line-seq path)` | `("a")` |
| `format` | `(format "%s=%d" :a 5)` | `":a=5"` |
