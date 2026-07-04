# rontolisp:http-handler

`(rontolisp:http-handler handler &optional port)`

Lisp のハンドラ関数で HTTP リクエストを処理します。`handler` は
（[`rontolisp:wasm-export`](rontolisp-wasm-export.md) と同様に）1 引数関数の名前を
指すクォート済みシンボルです。ハンドラはリクエストのプロパティリストを受け取り、
レスポンスのプロパティリストを返します。形は [`rontolisp:fetch`](rontolisp-fetch.md)
と対称で、送受信の HTTP を 1 つの値モデルで表します。

- **リクエスト** — `(:method <string> :path <string> :headers <alist> :body <string>)`
- **レスポンス** — `(:status <integer> :headers <alist> :body <string>)`。キーが無い場合は
  `:status 200`、本文は空がデフォルトです。

**インタープリタ** バックエンドでは、`http-handler` は `port`（デフォルト `8080`、
リクエストごとに 1 つの仮想スレッド）でブロッキングの組み込み HTTP サーバを起動し、
プロセスが停止されるまで（Ctrl-C）処理を続けます。

```console
(defun handle (request)
  (list :status 200
        :headers (list (cons "content-type" "text/plain"))
        :body (format nil "Hello from rontolisp!~%~a ~a~%"
                      (getf request :method) (getf request :path))))

(rontolisp:http-handler 'handle 8080)
```

実行して `curl` で通信します。

```console
$ java -jar rontolisp.jar app.lisp
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## バックエンド対応

`http-handler` は現在 **インタープリタ** バックエンドのみで動作します。**JVM**
バックエンドと **WASI コンポーネント** バックエンド（ハンドラを
`wasi:http/incoming-handler` コンポーネントにコンパイルし、`wasmtime serve` や Spin で
動かす）は開発中で、現時点ではコンパイル時に明確なエラーを出します。

完全な例は [HTTP サーバ](../../guides/tcp-sockets.md) を参照してください。
