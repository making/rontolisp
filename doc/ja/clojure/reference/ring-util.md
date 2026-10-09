# Ring ユーティリティ (ring.util, ring.middleware)

[Ring](https://github.com/ring-clojure/ring) の `ring-core`（1.15.5）と `ring-codec`（1.3.0）の
うち、よく使う純粋関数の部分を組み込みで提供します。ほかのライブラリと同じように require
すれば、同じソースがすべてのバックエンドで動きます。各名前空間は rontolisp に同梱した
Clojure ソースで、プロジェクトのファイルと同じ手順で読み込むので、その var は自分で書いた
var と同じように振る舞います（`:refer :all`、`#'`、関数値）。ソースパス上に同じ名前の
ファイルがあれば、クラスパスでソースディレクトリが依存ライブラリより先に来るのと同じく、
そちらが優先されます。これらで組み立てたハンドラは [Ring アダプター](ring.md)で提供します。

| 名前空間 | var |
|---|---|
| `ring.util.response` | `response` `status` `header` `content-type` `charset` `redirect` `redirect-status-codes` `redirect-after-post` `created` `not-found` `bad-request` `find-header` `get-header` `update-header` `get-charset` `set-cookie` `response?` `file-response` `url-response` `resource-response` `resource-data` |
| `ring.util.request` | `request-url` `content-type` `content-length` `character-encoding` `urlencoded-form?` `body-string` `path-info` `in-context?` `set-context` |
| `ring.util.codec` | `url-encode` `url-decode` `percent-encode` `percent-decode` `form-encode` `form-decode` `form-decode-str` `form-decode-map` `assoc-conj` `base64-encode` `base64-decode` |
| `ring.util.mime-type` | `default-mime-types` `ext-mime-type` |
| `ring.middleware.params` | `wrap-params` `params-request` `assoc-query-params` `assoc-form-params` |
| `ring.middleware.keyword-params` | `wrap-keyword-params` `keyword-params-request` |
| `ring.middleware.content-type` | `wrap-content-type` `content-type-response` |

```clojure
(require '[ring.util.response :as response] '[ring.util.codec :as codec])
(:headers (response/redirect "/login" :see-other))
; => {"Location" "/login"}
(response/get-charset (response/content-type (response/response "") "text/html; charset=utf-8"))
; => "utf-8"
(codec/url-decode "caf%C3%A9")
; => "café"
(codec/form-encode {"q" "a b"})
; => "q=a+b"
(get (codec/form-decode "a=1&a=2&b=x+y") "a")
; => ["1" "2"]
(codec/base64-encode (.getBytes "héllo"))
; => "aMOpbGxv"
(String. (codec/base64-decode "aMOpbGxv") "UTF-8")
; => "héllo"
```

ミドルウェアは Ring と同じようにハンドラを包みます。`wrap-params` は `:query-params`、
`:form-params`（`application/x-www-form-urlencoded` のボディから）、`:params` を加え、
`wrap-keyword-params` は `:params` のキーのうちキーワードとして読めるものをキーワードに
変えます。

```clojure
(ns app
  (:require [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.util.response :as response]))

(defn greet [{:keys [params]}]
  (response/response (str "Hello, " (:name params "stranger") "!")))

(def handler (-> greet wrap-keyword-params wrap-params))

(println (:body (handler {:request-method :get :uri "/greet"
                          :query-string "name=J%C3%BCrgen" :headers {}})))
```

```
Hello, Jürgen!
```

## ファイルとリソース

`file-response` は、`:root` の下でパスが指す `java.io.File` を `:body` とし、Ring と同じ
`Content-Length` と `Last-Modified` ヘッダーを付けたレスポンスを返します。そのファイルが
ない場合と、パスが `:root` の外に出る場合は `nil` を返します。ディレクトリは、
`:index-files?` が偽でなければ、その `index.html`、`index.htm`、または最初の `index.*` を
返します。`resource-response` はソースパスのリソース（[clojure.java.io](clojure-java-io.md)
のリソースを探すディレクトリにあるファイル）について、`url-response` は
`clojure.java.io/resource` の URL について同じことをし、`resource-data` はそれらが読む
マップを返します。jar のリソースはバイトストリームです。[Ring アダプター](ring.md)は
どちらのボディもそのまま送ります。この 4 つは、プログラムが最初にどれかの名前を挙げた
ところで `ring.util.response` に読み込むので、名前を挙げないプログラムはそのコードを
持ちません。

```console
clojure> (require '[ring.util.response :as response])
nil
clojure> (:headers (response/file-response "a.txt" {:root "www"}))
{"Content-Length" "11", "Last-Modified" "Tue, 02 Jan 2024 03:04:05 GMT"}
clojure> (response/file-response "../secret.txt" {:root "www"})
nil
```

## 組み込みでないもの

次のものは WebAssembly バックエンドにないホストを必要とするので、プログラムが名前を
挙げると、その名前を示して拒否します。

- 名前空間 `ring.middleware.cookies`、`session`、`flash`、`multipart-params`、
  `nested-params`、`not-modified`、`file`、`file-info`、`resource`、`head`、
  `content-length`、`ring.util.io`、`time`、`parsing`、`test`、`async`、
  `ring.websocket`。`ring.adapter.jetty` は `ring.adapter.rontolisp/run-server` を
  案内して拒否します。

## 違い

- 文字セットは文字列で指定します。`UTF-8`、`ISO-8859-1`、`US-ASCII` と、それらの JDK の
  別名を大文字小文字を問わず受け付けます。ほかの名前は、その名前をメッセージとする
  `IllegalArgumentException` を通知します。既定は UTF-8 で、不正な入力は JDK と同じ置き換え
  方でデコードします。
- `body-string` は `nil`、文字列、seq、ストリームを受け取る関数で、プログラムが拡張できる
  マルチメソッドではありません。
- `wrap-params` はフォームのボディを `slurp` で読みます。パーセントデコードには Ring と
  同じく `:encoding` オプション（またはリクエストの文字セット）を使います。
- `content-length` は ASCII の数字だけを読みます（Java の `Long/valueOf` はほかの文字体系の
  数字も受け付けます）。
- パスの正規化ではオラクルと同じくシンボリックリンクをすべて解決しますが、ファイルの
  パスが相対パスなら相対パスのまま扱います（WASM バックエンドは作業ディレクトリを
  知りません）。2 つの WASM バックエンドでは、絶対パスを指すリンクはたどりません（WASI
  ホストが拒否します）。そのためファイルは見つかりません。
- `resource-response` はソースパスのディレクトリにあるリソースだけを見つけます。実行時に
  組み立てた名前は jar の中では見つかりません（[clojure.java.io](clojure-java-io.md)）。
  jar のリソースは、名前をリテラルで書いた `clojure.java.io/resource` を `url-response` に
  渡せば配信できます。
- `resource-data` のメソッドは `:file` と `:jar` です。ほかのプロトコルの URL は、ここでの
  すべての[マルチメソッド](defmulti.md)と同じ文言で
  `No method in resource-data for dispatch value: :http` を通知します。
