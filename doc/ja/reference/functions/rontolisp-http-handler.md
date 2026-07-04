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

**インタープリタ** と **JVM** バックエンドでは、`http-handler` は `port`
（デフォルト `8080`、リクエストごとに 1 つの仮想スレッド）でブロッキングの
組み込み HTTP サーバを起動し、プロセスが停止されるまで（Ctrl-C）処理を続けます。
**WASI コンポーネント**（`--component`）にコンパイルすると、代わりに
`wasi:http/incoming-handler` をエクスポートし、`wasmtime serve` 上でサーバレス
HTTP コンポーネントとして動作します（`port` 引数は無視されます。ソケットは
ホストが所有します）。

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
$ rontolisp app.lisp
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

JVM クラスにコンパイルします（生成クラスは組み込みサーバのハンドラインタフェースを
実装するため、実行時に rontolisp の実行可能 JAR をクラスパスに含める必要が
あります — ネイティブバイナリではなく JAR が必要になるのはこのステップだけです）。

```console
$ rontolisp app.lisp -o App.class
$ java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. App
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

あるいは WASI HTTP コンポーネントにコンパイルし、`wasmtime serve` で提供します。

```console
$ rontolisp app.lisp -o app.wasm --component
$ wasmtime serve -W gc=y app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## バックエンド対応

`http-handler` は **インタープリタ** バックエンド（ブロッキングサーバ）、
**JVM** バックエンド（同じブロッキングサーバ。生成クラスの実行には rontolisp の
実行可能 JAR `rontolisp-0.1.0-SNAPSHOT-exec.jar` がクラスパスに必要）、
**WASI コンポーネント** バックエンド
（`--component`、`wasmtime serve` 用の `wasi:http/incoming-handler`
コンポーネント）で動作します。JVM と WASI コンポーネントのバックエンドでは、
リクエスト／レスポンスのヘッダはまだ受け渡しされません。ハンドラには
`:headers nil` が渡され、レスポンスの `:headers` は無視されます。ヘッダを
そのまま通すのはインタープリタだけです。

serve コンポーネントは純粋な WASI 0.2 なので wasmtime 専用ではありません。
`wasi:http` 0.2 を提供し WebAssembly GC プロポーザルを有効化できるホストであれば
実行できます — jco（Node.js 上の `jco serve`。V8 では wasm-GC がデフォルトで有効）
と wasmCloud（`gc` wasm プロポーザルを有効化した `wash`）のどちらでも動作します。
Spin（`spin up`）ではまだ動作しません。Spin の組み込み wasmtime は GC プロポーザルを
有効化しておらず、有効化するフラグも提供されていないためです。

完全な例とランタイムごとのコマンドは
[HTTP サーバガイド](../../guides/http-handler.md)を参照してください。
