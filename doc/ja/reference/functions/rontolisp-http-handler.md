# rontolisp:http-handler

`(rontolisp:http-handler handler &optional port &key raw-body)`

Lisp のハンドラ関数で HTTP リクエストを処理します。`handler` は
（[`rontolisp:wasm-export`](rontolisp-wasm-export.md) と同様に）1 引数関数の名前を
指すクォート済みシンボルです。ハンドラは Clack の環境プロパティリストを受け取り、
Clack のレスポンスリスト `(status headers body)` を返します — Clack
アプリケーションはそのまま有効なハンドラです
（[Clack Web アプリケーション](../../guides/clack.md)を参照）。

- **環境** — ちょうど次のキーを持つプロパティリストで、常にすべて存在します:
  `:request-method`（大文字化・intern 済みのキーワード。`:GET` / `:POST` /
  ...。`(eq m :POST)` が動きます）、`:script-name`（アプリケーションの
  マウントポイント。パーセントデコード済み — コンテキストパス配下に
  デプロイした Servlet war 以外では `""`）、`:path-info`
  （パーセントデコード済みのパス。マウントポイントを取り除いたもの）、`:query-string`（最初の `?` より後ろの
  生のテキスト、なければ `nil` —
  [`rontolisp:query-param`](rontolisp-query-param.md) /
  [`rontolisp:query-params`](rontolisp-query-params.md) でパースして
  ください）、`:server-name`、`:server-port`（整数）、`:server-protocol`
  （キーワード。例: `:HTTP/1.1`）、`:request-uri`（生のリクエストターゲット
  そのまま。エンコードされたまま、クエリ込み）、`:url-scheme`
  （`"http"`/`"https"`）、`:remote-addr` / `:remote-port`（インタープリタ／
  JVM では実際のピア。WASI コンポーネントでは `nil`）、`:headers`
  （小文字化した名前をキーとする `equal` ハッシュテーブル。重複ヘッダは
  `", "` で結合され、`nil` になることはありません —
  `(gethash "content-type" (getf env :headers))`）、`:content-type` と
  `:content-length`（文字列／整数、なければ `nil`）、そして `:raw-body`。
- **`:raw-body`** — デフォルト（`:raw-body :stream`）では非同期ストリーム
  です。読むハンドラは
  `(rontolisp:await (rontolisp:read-all (getf env :raw-body)))` で読み尽くし、
  [`rontolisp:async-defun`](../special-forms/rontolisp-async-defun.md)
  として定義する必要があります。ディレクティブ引数
  `(rontolisp:http-handler 'handle 8080 :raw-body
  :buffered)` を付けると、代わりにボディを全部読み切り、
  `read-line`/`read-char` と `read-byte`/`read-sequence` の両方で読めて
  本物の `file-position` を持つ、同期のインメモリな bivalent ストリーム
  として渡します — Clack アプリケーション（lack-request、http-body）が
  必要とする形です。ボディの無いリクエストでは
  `:raw-body` は `nil` になります。
- **レスポンス** — 位置引数のリスト `(status headers body)`。`status` は
  必須の整数です（car が整数でなければエラーを送出します）。`headers` は
  キーワード plist（`'(:content-type "text/plain")`）またはドット対の
  alist（[`rontolisp:fetch`](rontolisp-fetch.md) の結果の `:headers` を
  そのまま渡せます）。同名の繰り返しはそれぞれが独立したヘッダ行になり、
  `content-length` / `transfer-encoding` は落とされ（サーバが計算します）、
  `nil` でも構いません。`body` は文字列のリスト（連結されます）、
  `nil`／省略（空のボディ — 2 要素の `(status headers)` 形も有効）、
  `(unsigned-byte 8)` ベクタ、またはサーバが読み尽くす rontolisp の
  ストリーム（例: プロキシした fetch のボディ）です。**裸の文字列はエラーを
  送出します**（rontolisp の pathname はその namestring であり、Clack では
  pathname のボディが「このファイルを serve せよ」を意味するため）。関数の
  レスポンスは Clack の delayed 形のみ対応です —
  `(lambda (responder) ... (funcall responder (list 200 nil (list "later"))))`
  — streaming writer 形は拒否されます。

**インタープリタ** と **JVM** バックエンドでは、`http-handler` は `port`
（デフォルト `8080`、リクエストごとに 1 つの仮想スレッド）でブロッキングの
組み込み HTTP サーバを起動し、プロセスが停止されるまで（Ctrl-C）処理を続けます。
リスナーはワイルドカードアドレス（`0.0.0.0`、デュアルスタック）にバインドされます。
アドレス引数は無いため、バインド先を選びたいプログラムは
[`clack:clackup`](../../guides/clack.md) の `:address` を経由してください。
**WASI コンポーネント**（`--component`）にコンパイルすると、代わりに
`wasi:http/handler@0.3.0` をエクスポートし、`wasmtime serve` 上でサーバレス
HTTP コンポーネントとして動作します（`port` 引数は無視されます。ソケットは
ホストが所有します）。**Servlet war**（`-o app.war`）にコンパイルすると、
ソケットをバインドする代わりにハンドラをサーブレットコンテナに登録し、
`port` 引数は同様に無視されます —
[HTTP ガイド](../../guides/http-handler.md#compiled-to-a-servlet-war)を参照。

```console
(defun handle (env)
  (list 200 '(:content-type "text/plain")
        (list (format nil "Hello from rontolisp!~%~a ~a~%"
                      (getf env :request-method) (getf env :path-info)))))

(rontolisp:http-handler 'handle 8080)
```

インタープリタで実行して `curl` で通信します。

```console
$ rontolisp app.lisp
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

JVM クラスにコンパイルします（自己完結しています。組み込みサーバが
`am/ik/rontolisp/runtime/` としてクラスの隣に出力されるので、クラスパスに他は
不要です。`-o app.jar` なら両方が jar にまとまります）。

```console
$ rontolisp app.lisp -o App.class
$ java -cp . App
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

あるいは WASI HTTP コンポーネントにコンパイルし、`wasmtime serve` で提供します。

```console
$ rontolisp app.lisp -o app.wasm --component
$ wasmtime serve app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

## バックエンド対応

`http-handler` は **インタープリタ** バックエンド（ブロッキングサーバ）、
**JVM** バックエンド（同じブロッキングサーバ。生成クラスの隣に出力されるので
クラスパスに他は不要）、
**WASI コンポーネント** バックエンド
（`--component`、`wasmtime serve` 用の `wasi:http/handler@0.3.0`
コンポーネント）で動作します。リクエスト／レスポンスのヘッダは WASI
コンポーネントを含むすべてのバックエンドで受け渡しされます。ハンドラは
`:headers`（小文字化した名前をキーとする `equal` ハッシュテーブル）を
読み取り、レスポンスの `headers` 要素は書き戻されます。
serve コンポーネントのハンドラ内でも `random`、時刻系の組み込み関数、
`print`（ホストの標準出力への出力）は動作します — すべての `wasi:http`
ホストが提供する `wasi:random` / `wasi:clocks` / `wasi:cli` へブリッジ
されるためです。`uiop:getenv` はコンポーネント自身の
`wasi:cli/environment@0.3.0` インポート経由でホストの環境変数を読みます
（`wasmtime serve --env NAME=value` または `-S inherit-env=y`）。
ファイルストリームは利用できません。
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
指定した `wash dev` で実行します。**Spin** も
[canary ビルド](https://github.com/spinframework/spin/releases/tag/canary)
（4.1.0-pre0）以降なら、素の `spin.toml` だけでフラグ無しに実行できます。リリース版の
Spin 4.0.2 では動作しません — 組み込みの wasmtime 44 がリリース版の 0.3.0 では
なく `wasi:http@0.3.0-rc-2026-03-15` スナップショットを話すためです。
jco は 0.3 の非同期 ABI を実装していません。

完全な例とランタイムごとのコマンドは
[HTTP サーバガイド](../../guides/http-handler.md)を参照してください。
