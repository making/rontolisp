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
| `ring.util.response` | `response` `status` `header` `content-type` `charset` `redirect` `redirect-status-codes` `redirect-after-post` `created` `not-found` `bad-request` `find-header` `get-header` `update-header` `get-charset` `set-cookie` `response?` |
| `ring.util.request` | `request-url` `content-type` `content-length` `character-encoding` `urlencoded-form?` `body-string` `path-info` `in-context?` `set-context` |
| `ring.util.codec` | `url-encode` `url-decode` `percent-encode` `percent-decode` `form-encode` `form-decode` `form-decode-str` `form-decode-map` `assoc-conj` |
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

## 組み込みでないもの

次のものは WebAssembly バックエンドにないホストを必要とするので、プログラムが名前を
挙げると、その名前を示して拒否します。

- `ring.util.response/file-response`、`url-response`、`resource-response`、
  `resource-data`（`java.io.File`、URL、クラスローダーのリソースを使う）。
  `ring.util.codec/base64-encode` と `base64-decode`（バイト配列を使う）。
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
