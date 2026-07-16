# rontolisp:http-handler

`(rontolisp:http-handler handler &optional port)`

Lisp のハンドラ関数で HTTP リクエストを処理します。`handler` は
（[`rontolisp:wasm-export`](rontolisp-wasm-export.md) と同様に）1 引数関数の名前を
指すクォート済みシンボルです。ハンドラはリクエストのプロパティリストを受け取り、
レスポンスのプロパティリストを返します。形は [`rontolisp:fetch`](rontolisp-fetch.md)
と対称で、送受信の HTTP を 1 つの値モデルで表します。

- **リクエスト** — `(:method <string> :path <string> :query <string-or-nil>
  :headers <alist> :body <stream>)`。`:path` はクエリ文字列を取り除いたパスのみ、
  `:query` は先頭の `?` を除いた生のクエリ文字列（`/get?a=1&b=2` なら
  `"a=1&b=2"`）で、クエリが無いリクエストでは `nil` です —
  [`rontolisp:query-param`](rontolisp-query-param.md) /
  [`rontolisp:query-params`](rontolisp-query-params.md) でパースしてください。
  `:body` は非同期ストリームです。読むハンドラは
  `(rontolisp:await (rontolisp:read-all (getf request :body)))` で読み尽くし、
  [`rontolisp:async-defun`](../special-forms/rontolisp-async-defun.md)
  として定義する必要があります。
- **レスポンス** — `(:status <integer> :headers <alist> :body
  <string-or-stream>)`。キーが無い場合は `:status 200`、本文は空がデフォルト
  です。ストリームのボディ（例: プロキシした fetch レスポンスの `:body`）は
  サーバが読み尽くして送出します。

**インタープリタ** と **JVM** バックエンドでは、`http-handler` は `port`
（デフォルト `8080`、リクエストごとに 1 つの仮想スレッド）でブロッキングの
組み込み HTTP サーバを起動し、プロセスが停止されるまで（Ctrl-C）処理を続けます。
**WASI コンポーネント**（`--component`）にコンパイルすると、代わりに
`wasi:http/handler@0.3.0` をエクスポートし、`wasmtime serve` 上でサーバレス
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
$ wasmtime serve -W gc=y -W exceptions=y app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## バックエンド対応

`http-handler` は **インタープリタ** バックエンド（ブロッキングサーバ）、
**JVM** バックエンド（同じブロッキングサーバ。生成クラスの実行には rontolisp の
実行可能 JAR `rontolisp-0.1.0-SNAPSHOT-exec.jar` がクラスパスに必要）、
**WASI コンポーネント** バックエンド
（`--component`、`wasmtime serve` 用の `wasi:http/handler@0.3.0`
コンポーネント）で動作します。リクエスト／レスポンスのヘッダは WASI
コンポーネントを含むすべてのバックエンドで受け渡しされます。ハンドラは
`:headers`（`(名前 . 値)` という文字列ペアの連想リスト）を読み取り、
レスポンスに `:headers` があれば書き戻します。
serve コンポーネントのハンドラ内でも `random`、時刻系の組み込み関数、
`print`（ホストの標準出力への出力）は動作します — すべての `wasi:http`
ホストが提供する `wasi:random` / `wasi:clocks` / `wasi:cli` へブリッジ
されるためです。`getenv` は `nil` を返し、ファイルストリームは利用できません。
[`rontolisp:fetch`](rontolisp-fetch.md) もサービング中のハンドラ内で動作します
— serve と serve+fetch は 1 つのコンポーネント形状で、その
`wasi:http/client@0.3.0` インポートは `wasmtime serve` がデフォルトで提供します
— したがってプロキシ型のハンドラも同じ serve コマンドですべてのバックエンドで
動作します。await するハンドラ（たとえば serve 内の fetch）は非同期関数であり、
`defun` ではなく
[`rontolisp:async-defun`](../special-forms/rontolisp-async-defun.md)
で定義しなければなりません: `rontolisp:await` は非同期の本体の中でのみ
合法です。

ハンドラは [`rontolisp:wit-import`](rontolisp-wit-import.md)
で自前の WIT インターフェースを呼ぶこともできます。serve されるコンポーネントは
それを固定の `wasi:http` 表面と並べてインポートします。これが serve される
ハンドラが**状態**を保つ方法です — `wasi:http` ホストはリクエストごとに
コンポーネントを新しくインスタンス化するので、グローバルなハッシュテーブルは
毎回空で読み戻されますが、`wasi:keyvalue` のストアはその外側に生きています。

serve コンポーネントは非同期の `wasi:http@0.3.0`（`service` world）を
ターゲットとします。ハンドラは wasmtime 46+ でデフォルト有効な基本の
コンポーネントモデル非同期 ABI 上のコールバック非同期リフトであり、
`wasmtime serve` にゲートされた機能フラグは不要です。wasmCloud もホスト
します: リリース版の `wash`（2.5.2）が、
`dev.wasm_proposals: [gc, exception-handling, component-model-async]` を
指定した `wash dev` で実行します。jco は 0.3 の非同期 ABI を実装しておらず、
Spin の組み込み wasmtime は rontolisp のすべてのコンポーネントが必要とする
WebAssembly GC プロポーザルを有効化していません。

完全な例とランタイムごとのコマンドは
[HTTP サーバガイド](../../guides/http-handler.md)を参照してください。
