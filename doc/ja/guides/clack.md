# Clack Web アプリケーション

[Clack](https://github.com/fukamachi/clack) — Common Lisp
の Web アプリケーション環境 — は `(ql:quickload "clack")` で無改変のまま
ロードでき、`clack:clackup` は組み込みの `clack-handler-rontolisp`
バックエンドで Clack アプリケーションを実行します:

```console
$ cat app.lisp
(ql:quickload "clack")
(clack:clackup
 (lambda (env)
   (list 200 '(:content-type "text/plain")
         (list (format nil "Hello, Clack! ~A ~A~%"
                       (getf env :request-method) (getf env :path-info)))))
 :server :rontolisp
 :port 5000
 :use-thread nil)
$ rontolisp app.lisp        # interpret; or -o App.class / -o app.wasm --component
$ curl http://127.0.0.1:5000/hello
Hello, Clack! GET /hello
```

この裏に変換レイヤはありません: rontolisp 自身のサーバプロトコルが Clack の
プロトコル*そのもの*なので（[HTTP サーバ](http-handler.md)を参照）、
バックエンドはアプリケーションをハンドラとしてサーバに渡すだけで、
リクエストごとの変換を一切行いません — Clack アプリケーションはそのまま
有効な `rontolisp:http-handler` ハンドラであり、その逆も成り立ちます。

初回実行時に clack、[lack](https://github.com/fukamachi/lack) とその依存が
`~/.rontolisp/quicklisp` にダウンロードされます。依存は実ライブラリ
(alexandria、ironclad スライス) と
[組み込みシムシステム](asdf-systems.md#built-in-shim-systems)
(bordeaux-threads、usocket、swank、uiop) に解決されます。

## clackup のセマンティクス

デフォルト値は Clack ユーザーの期待どおりに動きます:

- **`:use-thread t` (デフォルト)** はサーバがバックグラウンドスレッド
  ([`rontolisp:make-thread`](../reference/functions/rontolisp-make-thread.md))
  で応答している間にハンドラオブジェクトを返し、`(clack:stop handler)` が
  そのサーバを停止します。
- **`:use-thread nil`** はフォアグラウンドで serve します: `clackup` は
  プロセスが停止される (Ctrl-C) までブロックします — 上のスクリプト形です。
- **`:use-default-middlewares t` (デフォルト)** は `lack:builder` を通じて
  アプリケーションを lack のバックトレースミドルウェアでラップします。
- `:address` はリスナーのバインド先 (デフォルト `127.0.0.1`)。`:silent t` は
  バナーを、`:debug nil` はデバッグ通知を抑制します。

## アプリケーションプロトコル

アプリケーションは、標準の Clack env plist を受け取り標準の
`(status headers body)` リストを返す関数です:

| env キー | 値 |
|---------|-------|
| `:request-method` | メソッドの大文字化・intern 済みキーワード (`:GET`、`:POST`、...) |
| `:script-name` | `""` |
| `:path-info` | パーセントデコード済みのリクエストパス |
| `:query-string` | 生のクエリ文字列、なければ `nil` |
| `:request-uri` | 生のリクエストターゲットそのまま (エンコードされたまま、クエリ込み) |
| `:server-name` / `:server-port` | `Host` ヘッダがあればそこから、なければリスナーの値 |
| `:server-protocol` | キーワード。例: `:HTTP/1.1` |
| `:url-scheme` | `"http"` または `"https"` |
| `:headers` | 小文字化したヘッダ名をキーとするハッシュテーブル (`:test 'equal`)。重複したリクエストヘッダはワイヤ順に `", "` で結合されます |
| `:content-type` / `:content-length` | 上のテーブルから (なければ `nil`。`:content-length` は整数) |
| `:raw-body` | リクエストボディの同期・インメモリな bivalent ストリーム — `read-line`/`read-char` と `read-byte`/`read-sequence` の両方が動き、本物の `file-position` を持ちます (lack-request と http-body が必要とする形)。ボディの無いリクエストでは `nil` |
| `:remote-addr` / `:remote-port` | インタープリタと JVM では実際のピア。WASI コンポーネントでは `nil` (`wasi:http@0.3.0` はピアのアクセサを公開しません) |

レスポンスの `body` は文字列のリスト・
`(vector (unsigned-byte 8))` (各オクテットがそのコードポイントの文字になる)・
rontolisp のストリーム・`nil` のいずれかで、2 要素の `(status headers)` 形も
有効です。裸の文字列 — したがって pathname のボディも (rontolisp の
pathname はその namestring です) — は明確なエラーを送出します。Clack 自身も
文字列を拒否します。関数のボディは Clack の delayed レスポンス形
(responder が最終的なレスポンスリストで呼ばれる形) に対応し、streaming
writer 形はエラーを送出します。

## バックエンド

- **インタプリタ** — 上記のすべて。
- **JVM クラス** — 同じプログラムを `-o App.class` でコンパイルします。serve
  するプログラムの常として実行時クラスパスに rontolisp の jar が必要です
  (`java -cp rontolisp-exec.jar:. App`)。
- **WASM コンポーネント** (`--component`) — ソケットはホストが所有します:
  `wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y
  -S inherit-network=y app.wasm` で実行します。`:port` 引数は無視され、
  `:use-thread` は実質 `nil` (WASM バックエンドはシングルスレッドなので、
  そこではデフォルトが `nil`)、`clack:stop` は意味を持ちません — サーバの
  ライフサイクルはホストが制御します。
- **WASM Preview 1** は設計上着信 TCP を持ちません: プログラムはコンパイル
  でき、`clackup` は実行時に `HTTP-HANDLER requires --component ...` を送出
  します (`handler-case` で捕捉可能)。

## ホストから呼ばれる場合: `clack-handler-cloudflare-workers`

ソケットを渡してこないホストもあります。Cloudflare Workers、ブラウザのページ、
node、JVM への埋め込みは、いずれもホスト側でリクエストを解析し、**エクスポート
された関数を呼び出します**。そこでは `clackup` に起動するものがありません —
それでも書くのは `clackup` であり、アプリケーションもソースの形も変える必要は
ありません。2 つ目の組み込みハンドラバックエンドが両者を橋渡しします。

```console
$ cat worker.lisp
(ql:quickload "clack-handler-cloudflare-workers")
(load "app.lisp")                       ; defines app, an ordinary Clack application

(clack:clackup #'app
               :server :cloudflare-workers
               :use-thread nil
               :use-default-middlewares nil)
```

(`app` は通常の Clack アプリケーションです。)

ここでの `run` は何も起動しません。アプリケーションを保存するだけで、ホストが
呼ぶエクスポート(`handle-request` — JSON のリクエスト文字列を受け取り JSON の
レスポンス文字列を返す)は、ハンドラバックエンドが残したマーカーからコンパイラが
合成します。ソース側でその名前に触れる箇所はなく、`--no-wasi` もそのまま使えます
— モジュールは何もインポートしません。

2 つのキーワードは暗記すべき定型句ではなく、このホストの性質そのものです:

- `:use-thread nil` — インタプリタと JVM では `clackup` はバックエンドを別スレッド
  で実行するのが既定で、そのままだとアプリケーションの保存が次のフォームより後に
  なります。WASM バックエンドでは元から既定値が nil です。
- `:use-default-middlewares nil` — lack の `backtrace` ミドルウェアはレポートを
  `*error-output*` に書きますが、ホスト駆動のリアクターにそれはありません。

インタプリタと JVM には合成すべきエクスポートがないので、ホストは合成された
エクスポートが呼ぶのと同じ関数を
`(clack.handler.cloudflare-workers:dispatch request-json)` と直接呼びます。
Worker なしで Worker を開発・テストできるのはこれによります。

その下にあるのが `handle` で、こちらは `clackup` も Worker も要りません。
2 引数の普通の関数です:

```lisp
(ql:quickload "clack-handler-cloudflare-workers")

(defun app (env)
  (list 200 '(:content-type "text/plain")
        (list (format nil "~a ~a ~a" (getf env :request-method)
                      (getf env :path-info) (getf env :query-string)))))

(princ (clack.handler.cloudflare-workers:handle
        #'app "{\"method\":\"GET\",\"target\":\"/hi?a=1\"}"))
```

```text
{"status":200,"headers":[["content-type","text/plain"]],"body":"GET /hi a=1"}
```

`handle` はアプリケーションと JSON のリクエスト文字列 1 つを受け取り、JSON の
レスポンス文字列 1 つを返します。Clack 環境の構築と Clack レスポンスの正規化は
serve 時とまったく同じ経路を通るので、アプリケーションからは Clack が約束する
ものがそのまま見えます。また `handle` は**エラーを捕捉します**: この種のホスト
では未捕捉のエラーはインスタンス全体を落とすため、代わりにコンディションの
report を載せた 500 を返します。

エンベロープは両方向とも次の形です:

```json
{ "method": "GET", "target": "/path?a=1", "headers": {"host": "..."},
  "body": "", "scheme": "https", "remote-addr": "203.0.113.7" }
```

```json
{ "status": 200, "headers": [["content-type", "text/plain"]], "body": "..." }
```

ホスト側で間違えやすい点が 2 つあります:

- `target` は**生の**リクエストターゲットです — パスとクエリは繋がったまま、
  パーセントエンコードされたままにします。分割とデコードは Lisp 側で行われ、
  アプリケーションが Clack の約束どおりのものを見るには `:path-info` /
  `:query-string` がそこから来る必要があります。
- ボディのあるリクエストでは `content-length` を送ってください。これがないと
  `lack/request` は何も解析せず、chunked で届いたリクエストにはそもそも付いて
  いません — 実際に読んだバイト数から設定してください。

レスポンスヘッダはオブジェクトではなく**ペアの配列**として渡されます。これに
より、Cookie を 2 つ設定するアプリケーションは `Set-Cookie` を 2 本返せます。

この方法で作った完全な Worker — アプリケーション、`worker.lisp`、JavaScript 側、
そして実測値 —は
[`examples/cloudflare-workers/httpbin-clack/`](https://github.com/making/rontolisp/tree/main/examples/cloudflare-workers/httpbin-clack)
にあります。

## 現在の制限

- Clack サーバはプロセスあたり 1 つ: 2 つ目の同時 `clackup` は 1 つ目の
  アプリケーションを置き換えます。
- `clack.socket` (WebSocket) と `:swank-port` は未対応です (`:swank-port` は
  `swank` スタブに到達し、エラーを送出します)。
- streaming writer 形のレスポンスと裸の文字列／pathname のボディは上記の
  とおりエラーを送出します (delayed 形の関数レスポンスは動作します)。

関連: 土台となるサーバは [HTTP を Serve する (http-handler)](http-handler.md)、
バックエンドごとの実行コマンド付きデモは
[`examples/asdf/clack-hello.lisp`](https://github.com/making/rontolisp/blob/main/examples/asdf/clack-hello.lisp)
を参照してください。
