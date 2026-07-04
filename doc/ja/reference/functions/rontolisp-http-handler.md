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
プロセスが停止されるまで（Ctrl-C）処理を続けます。**WASI コンポーネント**
（`--component`）にコンパイルすると、代わりに `wasi:http/incoming-handler` を
エクスポートし、`wasmtime serve` 上でサーバレス HTTP コンポーネントとして動作します
（`port` 引数は無視されます。ソケットはホストが所有します）。

```console
(defun handle (request)
  (list :status 200
        :headers (list (cons "content-type" "text/plain"))
        :body (format nil "Hello from rontolisp!~%~a ~a~%"
                      (getf request :method) (getf request :path))))

(rontolisp:http-handler 'handle 8080)
```

インタープリタで実行して `curl` で通信します。

```console
$ java -jar rontolisp.jar app.lisp
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

あるいは WASI HTTP コンポーネントにコンパイルし、`wasmtime serve` で提供します。

```console
$ java -jar rontolisp.jar app.lisp -o app.wasm --component
$ wasmtime serve -W gc=y -W component-model-async=y \
    -W component-model-async-stackful=y -W component-model-more-async-builtins=y \
    app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## バックエンド対応

`http-handler` は **インタープリタ** バックエンド（ブロッキングサーバ）と
**WASI コンポーネント** バックエンド（`--component`、`wasmtime serve` 用の
`wasi:http/incoming-handler` コンポーネント）で動作します。**JVM** バックエンドは
開発中で、現時点ではコンパイル時に明確なエラーを出します。

Spin（`spin up`）ではまだ動作しません。Spin の組み込み wasmtime は WebAssembly GC
プロポーザルを有効化しておらず、rontolisp のすべてのコンポーネントが GC を必要とする
ためです。`wasmtime serve -W gc=y ...` を使用してください。

完全な例は [HTTP サーバ](../../guides/tcp-sockets.md) を参照してください。
