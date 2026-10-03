# REPL

ファイルなしで `--source-language clojure` を付けると Clojure REPL
（`clojure> ` プロンプト）が始まります。1 フォームは複数行にまたがれます。完全性は
文字列とコメントの外側の `()[]{}` 括弧数で決まります。別々のプロンプトで入力された定義は
1 ファイルに書いたときと同様に互いを見えます:各バッファは走る前にトップレベルの
`def`/`defn` 名を宣言するので、後のバッファは先のバッファが定義したものを呼べます。
`(ns name)` や `(in-ns 'name)` のバッファは、その下の `::` キーワードが解決される
`*ns*` を切り替えます（ファイル自身の `ns` フォームと同様）。値の
エコーは Clojure 記法で可読に描画されます。トップレベルの `def`・`defn`・`defn-`・`defmacro`・
`defmulti`・`defonce` は定義した var（`#'user/twice`）をエコーし、束縛済みの var への `defonce` は
`nil` をエコーします。

```console
$ rontolisp --source-language clojure
clojure> (defn twice [x] (* 2 x))
#'user/twice
clojure> (twice 21)
42
```
