# HTTP サーバ（http-handler）

（[TCP ソケットガイド](tcp-sockets.md)の `http-hello.lisp` のように）
`read-line`/`write-line` で HTTP を手書きする方法も勉強になりますが、
素朴なリクエスト/レスポンス型のサーバであれば
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
がパースを引き受けてくれます。ハンドラはリクエストのプロパティリスト
（`:method` / `:path` / `:query` / `:headers` / `:body`）を受け取り、レスポンスの
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
（[`examples/net/http-handler.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/http-handler.lisp)
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
$ wasmtime serve -W gc=y -W exceptions=y app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

この場合モジュールは `wasi:http/incoming-handler` をエクスポートし、ソケットは
ホストが所有するため `port` 引数は無視されます。通常の rontolisp コンポーネントを
`wasmtime run` で実行する際に必要な `component-model-async` 系のフラグが一切
不要な点に注目してください。serve コンポーネントは純粋な WASI 0.2 であり、
ホストに要求するデフォルト外の機能は WebAssembly GC プロポーザル（`-W gc=y`）と
例外処理プロポーザル（`-W exceptions=y`。Lisp で書かれた HTTP グルーがボディの
終端検出に使用します）だけです。

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

## クエリ文字列

`:path` はパスのみを保持するため、ルーティングの比較は完全一致で書けます。
リクエストにクエリ文字列がある場合は、`?` より後ろの生のテキストが `:query`
として別途渡されます（クエリが無ければ `nil`）。URL ライブラリの
クエリ文字列関数
[`rontolisp:query-param`](../reference/functions/rontolisp-query-param.md) と
[`rontolisp:query-params`](../reference/functions/rontolisp-query-params.md)
でパースしてください（どちらもキーと値を URL デコードし、`nil` も受け付け
ます）:

```console
(defun handle (request)
  (list :status 200
        :body (format nil "Hello, ~a!~%"
                      (or (rontolisp:query-param (getf request :query) "name")
                          "world"))))

(rontolisp:http-handler 'handle 8080)
```

```console
$ curl 'http://127.0.0.1:8080/greet?name=ronto%20lisp'
Hello, ronto lisp!
$ curl http://127.0.0.1:8080/greet
Hello, world!
```

## ハンドラから他のサービスを呼び出す

[`rontolisp:fetch`](http-fetch.md) は 3 つのバックエンドすべてで、サービング中の
ハンドラ内でも動作します。古典的なプロキシ／アグリゲータの形が書けます:

```console
(defun handle (request)
  (let ((res (rontolisp:await
              (rontolisp:fetch "http://127.0.0.1:9000/upstream"))))
    (list :status (getf res :status)
          :body (getf res :body))))

(rontolisp:http-handler 'handle 8080)
```

WASI コンポーネントバックエンドでは、外向きリクエストの機構も同じコンポーネント
に同梱されるため、変更点はホストに外向き HTTP を許可することだけです —
wasmtime では `-S http=y` を付けます（`component-model-async` 系のフラグは
引き続き不要です）:

```console
$ rontolisp proxy.lisp -o proxy.wasm --component
$ wasmtime serve -W gc=y -S http=y proxy.wasm
```

完全な例は
[`examples/net/dog-fetcher.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/dog-fetcher.lisp)
です。[wasmCloud の dog-fetcher の例](https://wasmcloud.com/docs/v1/examples/rust/component/dog-fetcher/)
の再現で、リクエストごとに dog.ceo API からランダムな犬の画像 URL を取得して
JSON で応答します。

## 状態を保つ: グローバル変数ではなくストアへ

インタープリタと JVM ではサーバは 1 つの長命プロセスなので、グローバルなハッシュ
テーブルはリクエストを跨いで生き残ります。**serve されるコンポーネントではそう
なりません**。`wasi:http` ホストはリクエストごとにコンポーネントを新しく
インスタンス化するため、ハンドラが書いた内容は次のリクエストでは空で読み戻されます。

したがって状態を保つ方法は、それをコンポーネントの外 — ハンドラが*呼ぶ* WIT
インターフェース — に置くことです。束縛には
[`rontolisp:wit-import`](../reference/functions/rontolisp-wit-import.md)
を使います。serve されるコンポーネントは、それを固定の `wasi:http`
表面と並べてインポートします:

```console
(rontolisp:wit-import "wit/keyvalue.wit"
                      :interface "wasi:keyvalue/store@0.2.0-draft"
                      :package kv)

(defun handle (request)
  (let* ((page (getf request :path))
         (bucket (kv:open ""))
         (seen (kv:bucket-get bucket page))
         (hits (+ 1 (if seen (parse-integer seen) 0))))
    (kv:bucket-set bucket page (princ-to-string hits))
    (list :status 200 :body (format nil "~a: ~a hits~%" page hits))))

(rontolisp:http-handler 'handle 8080)
```

```console
$ rontolisp page-hits-server.lisp -o server.wasm --component
$ wasmtime serve -W gc=y -W exceptions=y -S keyvalue=y server.wasm
```

同じソースがインタープリタと JVM でも動きます。そこでは Lisp で書かれた
[プロバイダ](../reference/functions/rontolisp-wit-provide.md)
がインターフェースに応答します。コンポーネントでカウントが実際に*残る*かどうかは
ホストの都合です: wasmtime 組み込みのキーバリュープロバイダはインスタンスごとに
作り直されるインメモリストアなので (`wasmtime serve` の下ではリクエストごと)
残りませんが、プロセス外のプロバイダをリンクするホスト (たとえば wasmCloud の
`wash dev`) なら残ります。実例は
[`examples/wit/keyvalue`](https://github.com/making/rontolisp/tree/main/examples/wit/keyvalue)
にあります。

## 制限

リクエスト／レスポンスのヘッダは WASI コンポーネントを含むすべての
バックエンドで受け渡しされます。ハンドラはリクエストの `:headers`
（`(名前 . 値)` という文字列ペアの連想リスト）を読み取り、レスポンスに
`:headers` があれば書き戻します。

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
