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
`defprotocol` は名前（`P`）、`defrecord` と `deftype` はクラス名（`user.R`）、`declare` は最後の名前の var、
他のフォームに入れ子になった `def` もその var をエコーします。ファイルでは入れ子の `def` は値を返します。

oracle の REPL と同じく、`*1`、`*2`、`*3` は直近 3 つの入力の値（`ns` の入力は `nil` を
記録します）を、`*e` は入力が投げた最後の例外を持ちます。例外はこれらの値を変えません。
入力の読み込みや lowering での拒否は例外ではなく、何も記録しません。`*repl*` は `true` で
束縛されており、`*file*` は `"NO_SOURCE_PATH"`、`*source-path*` は `"NO_SOURCE_FILE"` です。

```console
$ rontolisp --source-language clojure
clojure> (defn twice [x] (* 2 x))
#'user/twice
clojure> (twice 21)
42
clojure> [*1 *2]
[42 #'user/twice]
clojure> (/ 1 0)
Error: Division by zero
clojure> (ex-message *e)
"Division by zero"
```
