# 入出力

`eval` IO 層上のファイル入口で、すべてのバックエンドで動きます。wasm では
`open`/`with-open-file` と同じく、ファイルにそれを含む `--dir` プリオープンが必要です。なければ
open はファイルがないときと同じく失敗します（`catch` には `java.io.FileNotFoundException`）。
[clojure.java.io](clojure-java-io.md) の File、URL、ストリームも受け付けます。`read-string` と
`read` は、`spit` が書いた内容を含むデータの読み戻しを、すべてのバックエンドで行います。`format` は
Clojure 記法の引数で Java 形式の文字列を描画します。

| Name | Example | Result |
|---|---|---|
| `spit` | `(spit path "a\n")` | `nil` |
| `slurp` | `(slurp path-or-reader)` | `"a\n"` |
| `line-seq` | `(line-seq path-or-reader)` | `("a")` |
| `file-seq` | `(file-seq (jio/file dir))` | the Files below `dir` |
| `clojure.java.io/reader` | `(jio/reader path-or-reader)` | a reader |
| `read-string` | `(read-string "[1 :k]")` | `[1 :k]` |
| `read` | `(read (java.io.PushbackReader. (jio/reader path)))` | the first datum |
| `reader-conditional` | `(reader-conditional '(:clj 1) false)` | `#?(:clj 1)` |
| `tagged-literal` | `(tagged-literal 'js {})` | `#js {}` |
| `default-data-readers` | `(get default-data-readers 'inst)` | `#'clojure.instant/read-instant-date` |
| `format` | `(format "%s=%d" :a 5)` | `":a=5"` |
| `with-open` | `(with-open [] :ok)` | `:ok` |
| `with-out-str` | `(with-out-str (print 1))` | `"1"` |
| `with-in-str` | `(with-in-str "a\nb" (read-line))` | `"a"` |
| `read-line` | `(read-line)` | the next line of `*in*` |
| `print-str` | `(print-str 1 "a")` | `"1 a"` |
| `prn-str` | `(prn-str 1 "a")` | `"1 \"a\"\n"` |
| `println-str` | `(println-str 1 "a")` | `"1 a\n"` |
