# 入出力

`eval` IO 層上のファイル入口です。インタプリタと JVM で動きます。wasm では
`open`/`with-open-file` と同じく、パスを含む `--dir` プリオープンが必要です。なければ
open が file-error を通知します。`format` は Clojure 記法の引数で Java 形式の文字列を描画します。

| Name | Example | Result |
|---|---|---|
| `spit` | `(spit path "a\n")` | `nil` |
| `slurp` | `(slurp path)` | `"a\n"` |
| `line-seq` | `(line-seq path-or-reader)` | `("a")` |
| `clojure.java.io/reader` | `(jio/reader path)` | a reader |
| `format` | `(format "%s=%d" :a 5)` | `":a=5"` |
| `with-open` | `(with-open [] :ok)` | `:ok` |
| `with-out-str` | `(with-out-str (print 1))` | `"1"` |
