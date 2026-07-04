# HTTP サーバ（http-handler）

（[TCP ソケットガイド](tcp-sockets.md)の `http-hello.lisp` のように）
`read-line`/`write-line` で HTTP を手書きする方法も勉強になりますが、
素朴なリクエスト/レスポンス型のサーバであれば
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
がパースを引き受けてくれます。ハンドラはリクエストのプロパティリスト
（`:method` / `:path` / `:headers` / `:body`）を受け取り、レスポンスの
プロパティリスト（`:status` / `:headers` / `:body`）を返します。これは
[`rontolisp:fetch`](http-fetch.md) と同じ値モデルで、送信ではなく受信側です。

```console
(defun handle (request)
  (list :status 200
        :headers (list (cons "content-type" "text/plain"))
        :body (format nil "Hello from rontolisp!~%~a ~a~%"
                      (getf request :method) (getf request :path))))

(rontolisp:http-handler 'handle 8080)
```

これを `app.lisp` として保存し
（[`examples/http-handler.lisp`](https://github.com/making/rontolisp/blob/develop/examples/http-handler.lisp)
としても同梱されています）、以下の 3 つのバックエンドのいずれかで実行します。

## インタープリタで実行する

`http-handler` はポート 8080 でブロッキングの組み込み HTTP サーバを起動し
（リクエストごとに 1 つの仮想スレッド）、プロセスが `Ctrl-C` で停止されるまで
処理を続けます。

```console
$ rontolisp app.lisp
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## JVM クラスにコンパイルする

同じソースは **JVM クラス** にもコンパイルでき、同じ方式で提供します。他の
コンパイル済み rontolisp プログラムと違い、このクラスは自己完結していません。
組み込みサーバのハンドラインタフェースを実装するため、実行時に rontolisp の
実行可能 JAR（`rontolisp-0.1.0-SNAPSHOT-exec.jar`。
[ビルドとインストール](../getting-started/build.md)と同じダウンロード物）を
クラスパスに含める必要があります。

```console
$ rontolisp app.lisp -o App.class
$ java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. App
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## WASI HTTP コンポーネントにコンパイルする

さらに **WASI HTTP コンポーネント** にもコンパイルでき、`wasmtime serve`
（wasmtime 46+）で動作します。

```console
$ rontolisp app.lisp -o app.wasm --component
$ wasmtime serve -W gc=y app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

この場合モジュールは `wasi:http/incoming-handler` をエクスポートし、ソケットは
ホストが所有するため `port` 引数は無視されます。通常の rontolisp コンポーネントを
`wasmtime run` で実行する際に必要な `component-model-async` 系のフラグが一切
不要な点に注目してください。serve コンポーネントは純粋な WASI 0.2 であり、
ホストに要求するデフォルト外の機能は WebAssembly GC プロポーザル（`-W gc=y`）
だけです。

## その他の WASI HTTP ランタイム

このコンポーネントがホストに要求するのは `wasi:http` 0.2 と wasm-GC だけなので、
実行できるランタイムは wasmtime に限りません。

**jco**（Bytecode Alliance の JavaScript ツールチェーン。Node.js/V8 上で動作し、
V8 では wasm-GC がデフォルトで有効）は追加設定なしで実行できます。

```console
$ npx @bytecodealliance/jco serve app.wasm --port 8080
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

**wasmCloud**（`wash` 2.x）は `gc` プロポーザルを有効化すれば実行できます。
`wash dev` の場合、プロジェクトの `.wash/config.yaml` でビルド済みコンポーネントを
指定し、プロポーザルを列挙します（no-op の `build.command` で wash 自身の
ビルドステップをスキップします）。

```yaml
build:
  command: "true"
  component_path: app.wasm
dev:
  wasm_proposals:
    - gc
```

```console
$ wash dev
$ curl http://127.0.0.1:8000/hello
Hello from rontolisp!
GET /hello
```

`wash host` にも同じスイッチが `--wasm-proposal gc`（または環境変数
`WASH_WASM_PROPOSALS=gc`）として用意されています。

**Spin**（`spin up`）ではまだ動作しません。組み込み wasmtime が、rontolisp の
すべてのコンポーネントが必要とする WebAssembly GC プロポーザルを有効化しておらず、
有効化するフラグも提供されていないためです。

## 制限

WASI コンポーネントバックエンドでは、リクエスト／レスポンスのヘッダはまだ
受け渡しされません。ハンドラには `:headers nil` が渡され、レスポンスの
`:headers` は無視されます。インタープリタと JVM バックエンドはヘッダを
そのまま受け渡しします。

serve コンポーネントのハンドラ内でも `random`、時刻系の組み込み関数、
`print`（ホストの標準出力への出力）はすべて動作します — コンポーネントが
これらを、すべての `wasi:http` ホストが提供する `wasi:random`・`wasi:clocks`・
`wasi:cli` インタフェースへブリッジするためです。`getenv` は `nil` を返し
（サービングホストは環境変数を公開しません）、ファイルストリームは利用
できません。
詳細は
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
のリファレンスページを参照してください。

HTTP の *クライアント* 側には `rontolisp:fetch` を使ってください —
[HTTP リクエストガイド](http-fetch.md)を参照してください。（任意の TCP
プロトコルや TLS など）生のソケットレベルで扱う場合は
[TCP ソケットガイド](tcp-sockets.md)を参照してください。
