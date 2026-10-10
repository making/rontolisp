# HTTP クライアント (rontolisp.http-client)

`rontolisp.http-client` は HTTP リクエストを送ります。API は
[babashka.http-client](https://github.com/babashka/http-client) と同じで、オプションマップを
受け取る `request` と、URL とオプションを受け取る `get`、`post`、`put`、`delete`、`head`、
`patch` があります。組み込みなので、`clojure.string` と同じように require します。
すべてのリクエストは [`rontolisp:fetch`](../../guides/http-fetch.md) を通るため、fetch が
使えるすべてのトランスポートで同じソースが動きます。

| ターゲット | ビルド | トランスポート |
|---|---|---|
| インタプリタ、JVM | `rontolisp app.clj`、`-o App.class` | JDK の `HttpClient` |
| WASM コンポーネント | `-o app.wasm --component`、`wasmtime run -S http=y` で実行 | `wasi:http` |
| WASM リアクター | `-o app.wasm --no-wasi --host-fetch` | ホストの `fetch`（Cloudflare Worker、node） |
| ネイティブ実行ファイル | `--native` | 実行ファイル自身のクライアント |

素の Preview 1 モジュールと、`--host-fetch` なしの `--no-wasi` モジュールにはトランスポートが
ありません。コンパイルはそのプログラムを拒否し、トランスポートを与えるフラグを示します。
`clojure.java.io` も同じ fetch で `http:` URL を読みます（[HTTP の URL](clojure-java-io.md#http-urls)）。

```clojure
(ns example (:require [rontolisp.http-client :as http]))

(let [r (http/get "https://httpbin.ik.am/get" {:headers {"accept" "application/json"}})]
  (println (:status r) (get-in r [:headers "content-type"])))
```

```
200 application/json
```

## オプション

| オプション | 意味 |
|---|---|
| `:uri`（または `:url`） | URL の文字列。`http` か `https` の絶対 URL |
| `:method`（または `:request-method`） | `:get`（既定）、`:head`、`:post`、`:put`、`:delete`、`:options`、`:patch` |
| `:headers` | 名前（文字列かキーワード）から文字列、または文字列の seq へのマップ。seq の要素はそれぞれ一つのフィールドとして送る |
| `:query-params` | URL のクエリに URL エンコードして連結するマップ。値がコレクションならキーを繰り返す |
| `:form-params` | `application/x-www-form-urlencoded` のボディとして送るマップ |
| `:body` | 文字列。バイト配列、`java.io.File`、入力ストリーム（`clojure.java.io` のストリーム、応答の `:as :stream` のボディ）はそのオクテットを送る。リーダーは終端まで読んで送る |
| `:multipart` | パートの seq。`:body` と `:form-params` に代わって `multipart/form-data` のボディとして送る |
| `:basic-auth` | `[user pass]` か `{:user ... :pass ...}`。`Authorization: Basic` ヘッダーになる |
| `:oauth-token` | `Authorization: Bearer` ヘッダーになる |
| `:accept` | `:json` で `Accept: application/json` |
| `:as` | `:string`（既定。ボディを UTF-8 としてデコードする）、`:bytes`（ボディのオクテットをバイト配列で渡す）、`:stream`（ボディを読まずに渡す） |
| `:decompress-body` | `false` なら圧縮されたボディを届いたまま渡す |
| `:throw` | `false` ならどのステータスもそのまま返す |
| `:async` | `true` ならレスポンスのフューチャーを返す |
| `:async-then`、`:async-catch` | `:async` と併用し、レスポンスに適用する関数と、失敗を表すマップに適用する関数 |

リクエストは、`:headers` が指定しない限り `Accept: */*` と
`Accept-Encoding: gzip, deflate` を送り、加えて fetch 自身の `User-Agent` を送ります。
それ以外の `:body` は `ex-info` を投げます。

## マルチパートのボディ

`:multipart` のパートは、`:name`（または `:part-name`）、`:content`（文字列、バイト配列、
`java.io.File`、入力ストリーム）と、省略可能な `:file-name`、`:content-type` からなるマップです。ボディは
babashka.http-client のものとオクテット単位で同じです。各パートは `Content-Disposition`
（`File` か `:file-name` があれば `filename` つき）、`Content-Type`（文字列は
`text/plain; charset=UTF-8`、`File` は拡張子から決め、それ以外は
`application/octet-stream`）、`Content-Transfer-Encoding` を持ちます。境界は
`babashka_http_client_Boundary` とランダムな UUID で、リクエストの `content-type` がそれを
示します（`:headers` で指定したものは置き換えます）。`--host-random` なしの `--no-wasi`
リアクターでは、UUID は `random` が使う生成器から引きます。出力したグルーがこれをシードします。

```clojure
(ns example (:require [rontolisp.http-client :as http]))

(let [r (http/post "https://httpbin.ik.am/post"
                   {:multipart [{:name "title" :content "hello"}
                                {:name "note" :file-name "note.txt" :content "a note"}]})]
  (println (:status r) (subs (get-in r [:request :headers "content-type"]) 0 30)))
```

```
200 multipart/form-data; boundary=
```

入力ストリームとして開けない `:content` は `IllegalArgumentException`
（`Cannot open <42> as an InputStream.`）を、存在しない `File` は
`java.io.FileNotFoundException` を投げます。

## レスポンス

`:status`、`:headers`（小文字の名前。応答が繰り返したフィールドは値のベクター）、`:body`、
`:uri`（リダイレクト後に応答した URL）、`:request`（送ったときのオプション）のマップです。
リダイレクト（301、302、303、307、308）は babashka.http-client の既定のクライアントと同じく
たどります。たどるのは 4 回までで、`https` から `http` へは移らず、303（と `POST` の 301、
302）はボディなしの `GET` にし、別のオリジンへ移るときはリクエストの `Authorization`、
`Cookie`、`Origin`、`Referer`、`Host` を外します。

200-207、300-304、307 以外のステータスは、`:throw false` でない限り
`Exceptional status code: N` という `ex-info` を投げます。そのデータはレスポンスです。

```clojure
(ns example (:require [rontolisp.http-client :as http]))

(println (try (http/get "https://httpbin.ik.am/status/404")
              (catch clojure.lang.ExceptionInfo e [(ex-message e) (:status (ex-data e))])))
(println (:status (http/get "https://httpbin.ik.am/status/404" {:throw false})))
```

```
[Exceptional status code: 404 404]
404
```

トランスポートの失敗（接続できない、転送が途中で切れたなど）は、トランスポートのメッセージを
持つ `java.io.IOException` を投げます。JDK が受け付けない URL は、呼び出しの時点で
`IllegalArgumentException` になります（`Illegal character in path at index 24: ...`、
`URI with undefined scheme`）。

## 圧縮された応答

`Content-Encoding` が `gzip` か `deflate`（zlib。サーバーがこの名前で送る生の DEFLATE も
含む）の応答は、`:as` が読む前に展開します。そのため `:body` はテキスト、`:as :stream` では
展開したオクテットです。`:headers` には符号化の名前が残ります。`HEAD` リクエスト、
`:decompress-body false`、それ以外の符号化では、ボディは届いたままです。符号化が示す形式に
なっていない応答は、`java.util.zip` と同じものを投げます。`java.util.zip.ZipException`
（`Not in GZIP format`、`Corrupt GZIP trailer`、`invalid block type` など）か、途中で
切れていれば `java.io.EOFException`（`Unexpected end of ZLIB input stream`）です。gzip の
ヘッダーは呼び出しの時点で、残りはボディを読むときに読みます。

```clojure
(ns example (:require [rontolisp.http-client :as http]))

(let [r (http/get "https://httpbin.ik.am/gzip")]
  (println (get-in r [:headers "content-encoding"]) (subs (:body r) 0 1)))
```

```
gzip {
```

## 非同期リクエスト

`:async true` はすぐにフューチャーを返します。`deref`（と `@`）はレスポンスを待ち、
`(deref f ms timeout-val)` は最大 `ms` ミリ秒だけ待ちます。`future?` は `true` で、
`future-done?` はレスポンスが届いたかを答えます。リクエストは取り消せないので
`future-cancel` は `false` を返し、`future-cancelled?` も `false` です。失敗は `deref` の
時点で、その例外を原因とする `java.util.concurrent.ExecutionException` として投げられます。

```clojure
(ns example (:require [rontolisp.http-client :as http]))

(let [f (http/get "https://httpbin.ik.am/get" {:async true})]
  (println (future? f) (:status @f) (future-done? f)))
```

```
true 200 true
```

Preview 1 モジュール（`--native`、`--host-fetch`）では、呼び出しが戻る前にリクエストが
完了するため、時間制限つきの `deref` もレスポンスを返します。

## ストリームとしての応答

`:as :stream` を指定すると、`:body` は応答のオクテットを読まずに保持する
`java.io.InputStream` です。JDK のクライアントの応答ストリーム、または圧縮された応答を
読むための `java.util.zip.GZIPInputStream` か `InflaterInputStream` です。`.read` は届いた
順に次のオクテットを返し（終端では `-1`）、バイト配列への `.read` は手元に届いたオクテットで
配列を埋めます。`.readNBytes` と `.readAllBytes` は求めた分がそろうまで読みます。`.skip`、
`.available`、`.transferTo`、`clojure.java.io/copy`（`File` か出力ストリームへ、オクテットの
まま）もこれを受け付けます。
`slurp` と `clojure.java.io/reader` はテキストとして読み（`:encoding` で別の文字セットを
指定しない限り UTF-8）、`with-open` と `.close` は閉じます。Ring ハンドラがこれを
レスポンスの `:body` として返すと、オクテットは届いた順にそのまま中継されます。

```clojure
(ns example
  (:require [rontolisp.http-client :as http]
            [clojure.java.io :as io]))

(let [body (:body (http/get "https://httpbin.ik.am/get" {:as :stream}))]
  (println (instance? java.io.InputStream body) (char (.read body)))
  (.close body))
```

```
true {
```

Ring のプロキシ:

```console
$ cat proxy.clj
(ns proxy
  (:require [ring.adapter.rontolisp :refer [run-server]]
            [rontolisp.http-client :as http]))

(defn handler [req]
  (let [r (http/get (str "https://example.com" (:uri req)) {:as :stream :throw false})]
    {:status (:status r) :body (:body r)}))

(run-server handler {:port 3000})
$ rontolisp proxy.clj -o src/worker.wasm --no-wasi --host-fetch --host-boundary=streaming --emit-js-glue
```

## 相違点

- レスポンスに `:version` はなく、`:uri` は文字列です（babashka.http-client では
  `java.net.URI`）。
- `:stream` のボディに対する `clojure.java.io/reader` は、最初の行を返す前に全体を
  読みます（ボディ自体に対する `.read` のループは、届いたオクテットから順に読みます）。
  JVM では、トランスポートが応答全体を受け取ってからレスポンスを返します。
- 名前を挙げて拒否するもの: オプションの `:client`、`:interceptors`、`:timeout`、
  `:version`、`:raw`、`:expect-continue`、`java.net.http` のクライアントを作る var の
  `client`、`default-client-opts` と `->` で始まるビルダー、そして名前空間
  `babashka.http-client` そのもの（このページを案内します）。
- リーダーはそのテキストを送ります（babashka.http-client はリーダーを受け付けません）。
  Ring のリクエストの `:body` はここではリーダー（Jetty では入力ストリーム）なので、
  バイナリのアップロードをそのまま送り出すと、オクテット単位では一致しません。
- トランスポートの失敗は `java.io.IOException` です（babashka.http-client では
  `java.net.ConnectException` などのサブクラス）。
- `--host-fetch` ではホスト自身の `fetch` がリダイレクトを（20 回まで）たどるため、`:uri` は
  リクエストした URL になります。また応答の展開もホストが行うため、`:headers` にはその
  `content-encoding` と `content-length` がありません。
