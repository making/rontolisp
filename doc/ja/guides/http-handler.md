# HTTP サーバ（http-handler）

（[TCP ソケットガイド](tcp-sockets.md)の `http-hello.lisp` のように）
`read-line`/`write-line` で HTTP を手書きする方法も勉強になりますが、
素朴なリクエスト/レスポンス型のサーバであれば
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
がパースを引き受けてくれます。ハンドラは Clack の環境プロパティリスト
（`:request-method` / `:path-info` / `:query-string` / `:headers` /
`:raw-body` / ...）を受け取り、Clack のレスポンスリスト
`(status headers body)` を返します。これは
[Clack Web アプリケーション](clack.md)のプロトコルそのものであり、Clack
アプリケーションがリクエストごとの変換なしで serve できる理由です:

```console
(defun handle (env)
  (list 200 '(:content-type "text/plain")
        (list (format nil "Hello from rontolisp!~%~a ~a~%"
                      (getf env :request-method) (getf env :path-info)))))

(rontolisp:http-handler 'handle 8080)
```

これを `app.lisp` として保存し
（[`examples/net/http-handler.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/http-handler.lisp)
としても同梱されています）、以下の 3 つのバックエンドのいずれかで実行します。

## ハンドラの契約

ハンドラは Clack の環境プロパティリストを受け取ります。キーは以下のとおりで、
常にすべて存在します:

| env キー | 値 |
|---------|-------|
| `:request-method` | メソッドの大文字化・intern 済みキーワード（`:GET`、`:POST`、...）。`(eq m :POST)` が動きます |
| `:script-name` | 常に `""` |
| `:path-info` | パーセントデコード済みのリクエストパス |
| `:query-string` | 最初の `?` より後ろの生のテキスト、なければ `nil` |
| `:server-name` / `:server-port` | `Host` ヘッダがあればそこから、なければリスナーの値 |
| `:server-protocol` | キーワード。例: `:HTTP/1.1` |
| `:request-uri` | 生のリクエストターゲットそのまま（エンコードされたまま、クエリ込み） |
| `:url-scheme` | `"http"` または `"https"` |
| `:remote-addr` / `:remote-port` | インタープリタと JVM では実際のピア。WASI コンポーネントでは `nil`（`wasi:http@0.3.0` はピアのアクセサを公開しません） |
| `:headers` | **小文字化した**ヘッダ名をキーとする `equal` ハッシュテーブル — `(gethash "content-type" (getf env :headers))` で引きます。重複したヘッダは `", "` で結合され、`nil` になることはありません |
| `:content-type` / `:content-length` | 上のテーブルから（なければ `nil`。`:content-length` は整数） |
| `:raw-body` | リクエストボディ（下記） |

デフォルトの `:raw-body` は rontolisp の**非同期**ストリームです。読む
ハンドラは
`(rontolisp:await (rontolisp:read-all (getf env :raw-body)))` で読み尽くし、
[`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md)
として定義する必要があります。ディレクティブのオプション引数
`(rontolisp:http-handler 'handle 8080 :raw-body :buffered)` を付けると、
代わりにボディを先に全部読み切り、**同期**のインメモリな bivalent
ストリーム — `read-line`/`read-char` *と* `read-byte`/`read-sequence`
の両方で読め、本物の `file-position` を持つ — として渡します。これが
Clack アプリケーション（lack-request、http-body）が必要とする形です。
ボディの無いリクエストでは `:raw-body` は `nil` になります。

ハンドラは Clack の位置引数のレスポンスリスト `(status headers body)`
を返します:

- `status` — **必須**の整数。car が整数でなければエラーを送出します。
- `headers` — キーワード plist（`'(:content-type "text/plain")`、慣用形）
  またはドット対の alist — 後者を受け付けるので
  [`rontolisp:fetch`](http-fetch.md) の結果の `:headers` をそのまま渡せ
  ます。同名の繰り返しはそれぞれが独立したヘッダ行になり（`:set-cookie`
  の繰り返しは構造上正しくなります）、`content-length`/`transfer-encoding`
  は落とされます（サーバが計算します）。`nil` でも構いません。
- `body` — **文字列のリスト**（連結されます）、`nil` または省略（空の
  ボディ — 2 要素の `(status headers)` 形も有効です）、`(unsigned-byte 8)`
  ベクタ（1 バイトずつそのまま書き出されるので、バイナリのレスポンスは
  バイト単位で正確です）、または rontolisp のストリーム（例: プロキシした
  fetch のボディ）。
  **裸の文字列はエラーを送出します** — これは意図的で、文字列を拒否する
  Clack に忠実な挙動です。pathname のボディは「このファイルを serve せよ」を
  意味し (lack の静的ファイルミドルウェアが返します)、ここでは独立した値
  なので、トランスポートが配信できるようになるまで未対応として拒否されます。関数のレスポンスは Clack の delayed 形のみ対応です —
  `(lambda (responder) ... (funcall responder (list 200 nil (list "later"))))`
  — streaming writer 形は拒否されます。

Clack 以前の契約で書かれたハンドラの移行では、次の落とし穴に注意して
ください: レスポンス側は（上記のエラーで）大きな音を立てて失敗しますが、
リクエスト側は静かに失敗します — 移行しきれていないハンドラの
`(getf env :method)` は単に `nil` を返すだけです。

*クライアント*側は変わっていません: [`rontolisp:fetch`](http-fetch.md) は
これまでどおり `(:status <integer> :headers <alist> :body <stream>)` の
結果 plist を返します。

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
（wasmtime 47+ — 46 でも動作はしますが、同時アクセスで崩壊します。後述の
スループットの節を参照）で動作します。

```console
$ rontolisp app.lisp -o app.wasm --component
$ wasmtime serve -W gc=y -W exceptions=y app.wasm
$ curl http://127.0.0.1:8080/hello
Hello from rontolisp!
GET /hello
```

この場合モジュールは `wasi:http/handler@0.3.0`（非同期の WASI 0.3 HTTP
world）をエクスポートし、ソケットはホストが所有するため `port` 引数は無視
されます。フラグはコンポーネントが実際に使う機能を有効化するものです:
WebAssembly GC プロポーザル（`-W gc=y`）と例外処理プロポーザル
（`-W exceptions=y`。Lisp で書かれた HTTP グルーがボディの終端検出に使用
します）です。ハンドラは**コールバック非同期リフト**のエクスポートで、
サスペンドしたハンドラ（タイマ・fetch・ボディ読みの await）は制御をホスト
に返し、各完了イベントはコンポーネントのコールバック経由で届けられます —
いずれも wasmtime 46+ でデフォルト有効な基本のコンポーネントモデル非同期
ABI の一部であり、ゲートされた機能フラグは不要です。レスポンスは従来どおり
`canon task.return` を通じてタスクの途中で届けられ、その後にボディが
ストリームされます。

## その他の WASI HTTP ランタイム

このコンポーネントがホストに要求するのは `wasi:http` **0.3**（非同期）と
wasm-GC です。wasmtime 46+ がこれをホストし、**wasmCloud** もホストします:
`wash` 2.5.2 が、プロジェクトマニフェストに
`dev.wasm_proposals: [gc, exception-handling, component-model-async]` を
指定した `wash dev` で実行します。インストールは
`curl -fsSL https://wasmcloud.com/sh | bash` で行ってください（wash 2.6.1
で検証済み）。なお別リポジトリ `wasmCloud/wash` からタグ指定で取得できる
バイナリは別系統の古い版で、2.0.0-rc.x は `wasi:http` を **0.2** しか提供せず、
インターフェース抽出の段階でコンポーネントを拒否します。

**Spin** も
[canary ビルド](https://github.com/spinframework/spin/releases/tag/canary)
（4.1.0-pre0）以降で実行できます — 組み込みの
wasmtime が 47 になり、WebAssembly GC と例外処理のプロポーザルがデフォルトで
有効なので、フラグは不要です。プログラムの隣に `spin.toml` を置きます:

```toml
spin_manifest_version = 2

[application]
name = "rontolisp-http-handler"
version = "0.1.0"

[[trigger.http]]
route = "/..."
component = "hello"

[component.hello]
source = "app.wasm"

[component.hello.build]
command = "rontolisp app.lisp -o app.wasm --component"
```

```console
$ spin build && spin up
Serving http://127.0.0.1:3000
$ curl http://127.0.0.1:3000/hello
Hello from rontolisp!
GET /hello
```

ソケットは Spin が所有し **3000** 番で待ち受けるため、ここでも `port` 引数は
無視されます。ハンドラが [`rontolisp:fetch`](http-fetch.md) を呼ぶ場合は、
接続先ホストをコンポーネントの `allowed_outbound_hosts` に登録する必要が
あります — Spin はデフォルトで外向き HTTP を拒否します:

```toml
[component.dog]
source = "app.wasm"
allowed_outbound_hosts = ["https://dog.ceo"]
```

リリース版の Spin **4.0.2 では動作しません**。組み込みの wasmtime が 44 で、
リリース版の `wasi:http@0.3.0` ではなく `wasi:http@0.3.0-rc-2026-03-15`
スナップショットを話すため、GC を有効にしてもインポートのリンクに失敗します
（そもそもリリース版 4.0.2 のバイナリには GC を有効にする手段がありません:
`--experimental-wasm-feature` オプションは canary ビルドにのみ組み込まれて
います）。**jco** もまだ実行できません — 0.3 の非同期 ABI が未実装です。

## クエリ文字列

`:path-info` は（パーセントデコード済みの）パスのみを保持するため、
ルーティングの比較は完全一致で書けます。リクエストにクエリ文字列がある
場合は、最初の `?` より後ろの生のテキストが `:query-string` として別途
渡されます（クエリが無ければ `nil`）。URL ライブラリの
クエリ文字列関数
[`rontolisp:query-param`](../reference/functions/rontolisp-query-param.md) と
[`rontolisp:query-params`](../reference/functions/rontolisp-query-params.md)
でパースしてください（どちらもキーと値を URL デコードし、`nil` も受け付け
ます）:

```console
(defun handle (env)
  (list 200 '(:content-type "text/plain")
        (list (format nil "Hello, ~a!~%"
                      (or (rontolisp:query-param (getf env :query-string) "name")
                          "world")))))

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
ハンドラ内でも動作します。古典的なプロキシ／アグリゲータの形が書けます。
await するハンドラは非同期関数なので、`defun` ではなく
[`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md)
で定義してください:

```console
(rontolisp:async-defun handle (env)
  (let ((res (rontolisp:await
              (rontolisp:fetch "http://127.0.0.1:9000/upstream"))))
    (list (getf res :status) (getf res :headers) (getf res :body))))

(rontolisp:http-handler 'handle 8080)
```

fetch の結果の `:headers` alist はそのままレスポンスの `headers` スロットに、
`:body` ストリームは `body` スロットに入ります — ストリームはサーバが
読み尽くします。

WASI コンポーネントバックエンドでは、外向きリクエストの機構も同じコンポーネント
に同梱されます — serve と serve+fetch は 1 つのコンポーネント形状で、
インポートする `wasi:http/client@0.3.0` は `wasmtime serve` がデフォルトで
提供します（`-S http=y` は不要です）:

```console
$ rontolisp proxy.lisp -o proxy.wasm --component
$ wasmtime serve -W gc=y -W exceptions=y proxy.wasm
```

完全な例は
[`examples/net/dog-fetcher.lisp`](https://github.com/making/rontolisp/blob/develop/examples/net/dog-fetcher.lisp)
です。[wasmCloud の dog-fetcher の例](https://wasmcloud.com/docs/v1/examples/rust/component/dog-fetcher/)
の再現で、リクエストごとに dog.ceo API からランダムな犬の画像 URL を取得して
JSON で応答します。

## 状態を保つ: グローバル変数ではなくストアへ

インタープリタと JVM ではサーバは 1 つの長命プロセスなので、グローバルなハッシュ
テーブルはリクエストを跨いで生き残ります。**serve されるコンポーネントではそう
なりません** — しかもその壊れ方は「毎回リセットされる」よりも厄介です。
インスタンスがどれだけ生きるかはホストの判断であり、ホストごとに異なります:

| ホスト | インスタンスの寿命 |
|---|---|
| `wasmtime serve` | 128 リクエストで破棄 (`--max-instance-reuse-count`) |
| Spin | 128 リクエスト (wasmtime の既定値をそのまま継承) |
| wasmCloud `wash dev` | 1 リクエスト — 常に新しいインスタンス |

つまりグローバル変数は、実行中ずっと残るわけでもリクエストごとに戻るわけでも
ありません。wasmtime と Spin では 128 リクエストごとにトップレベルが再実行され、
ハンドラがグローバルに溜めたものはそこで消えます。トップレベルの副作用は
冪等に書き、残さなければならない状態はコンポーネントの外に置いてください。

したがって状態を保つ方法は、それをコンポーネントの外 — ハンドラが*呼ぶ* WIT
インターフェース — に置くことです。束縛には
[`rontolisp:wit-import`](../reference/functions/rontolisp-wit-import.md)
を使います。serve されるコンポーネントは、それを固定の `wasi:http`
表面と並べてインポートします:

```console
(rontolisp:wit-import "wit/keyvalue.wit"
                      :interface "wasi:keyvalue/store@0.2.0-draft"
                      :package kv)

(defun handle (env)
  (let* ((page (getf env :path-info))
         (bucket (kv:open ""))
         (seen (kv:bucket-get bucket page))
         (hits (+ 1 (if seen (parse-integer seen) 0))))
    (kv:bucket-set bucket page (princ-to-string hits))
    (list 200 nil (list (format nil "~a: ~a hits~%" page hits)))))

(rontolisp:http-handler 'handle 8080)
```

```console
$ rontolisp page-hits-server.lisp -o server.wasm --component
$ wasmtime serve -W gc=y -W exceptions=y -S keyvalue=y server.wasm
```

同じソースがインタープリタと JVM でも動きます。そこでは Lisp で書かれた
[プロバイダ](../reference/functions/rontolisp-wit-provide.md)
がインターフェースに応答します。コンポーネントでカウントが実際に*残る*かどうかは
ホストの都合です: wasmtime 組み込みのキーバリュープロバイダはリクエストごとに
空から始まるインメモリストアなので (実測: どのリクエストも 1 hit を返します)
残りませんが、プロセス外のプロバイダをリンクするホスト (たとえば wasmCloud の
`wash dev`) なら残ります。実例は
[`examples/wit/keyvalue`](https://github.com/making/rontolisp/tree/develop/examples/wit/keyvalue)
にあります。

## スループットと、コンポーネントが払っているコスト

単純なハンドラであれば 3 つのバックエンドは同程度です。1 台のマシンでの計測
(同時接続 16、10 秒のクローズドループ、`"Hello " + :path-info` を返すハンドラ、
wasmtime 47.0.2、`wasmtime serve` は既定設定):

| バックエンド | requests/s | mean | p99 |
|---|---|---|---|
| インタープリタ | 33 900 | 0.47 ms | 0.99 ms |
| JVM クラス | 36 600 | 0.44 ms | 0.88 ms |
| WASI コンポーネント | 24 500 | 0.65 ms | 1.19 ms |

コンポーネントの行には **wasmtime 47 以降**が必要です。wasmtime 46 では、
実行時の型テストの*失敗* — 動的言語の型ディスパッチが普通に起こすミス —
がエンジン全体で共有されるロックを取るホスト呼び出しになります。接続が
1 本ならただ払うだけ（約 10%）ですが、同時接続はこのロックを奪い合い、
接続を増やすほどスループットが*下がります* — 16 接続でおよそ 1/15 です。
wasmtime 47 はこの型チェックをインライン化し（rontolisp が出力する型は
すべて final で、これはまさにその高速パスが要求する形です）、崩壊は
消えます。

コンポーネントの差はハンドラではなく**インスタンス化**です。ホストはインスタンス
ごとにトップレベル全体 (`_start`) を 1 回実行し、`--max-instance-reuse-count`
リクエストごとにインスタンスを破棄します。このつまみを下げるとコストが見えます —
`--max-instance-reuse-count 1` では同じコンポーネントのスループットは上表の
1/3 程度まで落ちます。リクエストごとにインスタンス化を丸ごと払うからです。

知っておく価値のある帰結が 2 つあります:

- **トップレベルに何を置くかが効き、その度合いはホスト次第**。トップレベルの
  処理はインスタンスごとに 1 回なので、wasmtime と Spin では 128 リクエストに
  償却されますが、wasmCloud では*毎リクエスト*払います（同じハンドラが
  wasmtime の 24500 rps に対して 7900 rps）。`ql:quickload "clack"` する
  プログラムと素の `rontolisp:http-handler` のプログラムが wasmtime 上では
  ほぼ同じ速度なのにまさにこの理由で、wasmCloud 上では目に見えて差が出ます。
- **`--optimize` はここでは速度ではなくサイズのため**。コンパイル済みのコア
  モジュールをツリーシェイクし (serve コンポーネントで数 %、serve でない
  コンポーネントなら 90% 減ることもあります)、インスタンス化はわずかに
  短くなりますが、定常状態の 1 リクエストあたりのコストは変わりません。

## 制限

リクエスト／レスポンスのヘッダは WASI コンポーネントを含むすべての
バックエンドで受け渡しされます。ハンドラは環境の `:headers`
（小文字化したヘッダ名をキーとする `equal` ハッシュテーブル）を読み取り、
レスポンスの `headers` 要素は書き戻されます。

serve コンポーネントのハンドラ内でも `random`、時刻系の組み込み関数、
`print`（ホストの標準出力への出力）はすべて動作します — コンポーネントが
これらを、すべての `wasi:http` ホストが提供する `wasi:random`・`wasi:clocks`・
`wasi:cli` インタフェースへブリッジするためです。`uiop:getenv` もホストの
環境変数を読みます — serve コンポーネントは `wasi:cli/environment@0.3.0` を
インポートするので、`wasmtime serve --env NAME=value`（または
`-S inherit-env=y`）がハンドラに届きます。ファイルストリームは利用
できません。
詳細は
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
のリファレンスページを参照してください。

HTTP の *クライアント* 側には `rontolisp:fetch` を使ってください —
[HTTP リクエストガイド](http-fetch.md)を参照してください。（任意の TCP
プロトコルや TLS など）生のソケットレベルで扱う場合は
[TCP ソケットガイド](tcp-sockets.md)を参照してください。
