# Ring アダプター (ring.adapter.rontolisp)

`ring.adapter.rontolisp/run-server` は、[Ring](https://github.com/ring-clojure/ring)
のハンドラ（リクエストマップを受け取ってレスポンスマップを返す関数）を、ターゲット自身の
HTTP 受信トランスポートで提供します。組み込みなので、`clojure.string` と同じように require
します。Clack バックエンドの `:server :rontolisp`（[Clack](../../guides/clack.md)）が対応する
すべてのトランスポートで、同じソースが動きます。両アダプターは同じトランスポートのコードを
呼び出します。

| ターゲット | ビルド | トランスポート |
|---|---|---|
| インタプリタ | `rontolisp app.clj` | プログラムがバインドするソケット（`:port`、`:host`） |
| JVM | `-o App.class`、`-o app.jar` | 同じソケットサーバー |
| Servlet war | `-o app.war` | ポートはコンテナが持ち、`run-server` はハンドラを登録して戻る |
| WASM コンポーネント | `-o app.wasm --component`、`wasmtime serve` で実行 | ソケットはホストが持ち、`run-server` はすぐ戻る |
| WASM リアクター | `-o app.wasm --no-wasi` | ホストが `handle-request` エクスポートを呼ぶ（[Clack ガイド](../../guides/clack.md)のリアクタビルド） |

素の Preview 1 モジュール（`-o app.wasm`）は接続を受け付けません。コンパイルはでき、
実行時に `run-server` が `--component` が必要だというエラーを通知します。

| Name | Example | Result |
|---|---|---|
| `ring.adapter.rontolisp/run-server` | `(run-server handler {:port 3000})` | ブロックする。`:join? false` ならサーバーを返す |

```console
$ cat app.clj
(ns app (:require [ring.adapter.rontolisp :refer [run-server]]))

(defn handler [{:keys [request-method uri]}]
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body (str (name request-method) " " uri "\n")})

(run-server handler {:port 3000 :host "127.0.0.1"})
$ rontolisp app.clj &
$ curl http://127.0.0.1:3000/hello
get /hello
```

## リクエストマップ

| キー | 値 |
|---|---|
| `:request-method` | 小文字キーワードのメソッド: `:get`、`:post` など |
| `:uri` | 送られたままの（パーセントデコードしない）パス。クエリは含まない |
| `:query-string` | `?` より後のテキスト。なければ `nil` |
| `:headers` | 小文字のヘッダー名から値へのマップ。繰り返されたヘッダーは `", "` で連結 |
| `:server-name`、`:server-port` | `Host` ヘッダーから。なければ待ち受けアドレス |
| `:remote-addr` | 接続元アドレス。コンポーネントと、ホストが送らないリアクターでは `nil` |
| `:scheme` | `:http` または `:https` |
| `:protocol` | `"HTTP/1.1"` |
| `:content-type`、`:content-length` | ヘッダーから。なければ `nil` |
| `:body` | リクエストボディの入力ストリーム。ボディのないリクエストでは `nil` |

`:body` は `slurp`、`line-seq`、`clojure.java.io/reader` で読みます。その上の
`java.io.InputStreamReader`（または `BufferedReader`）はどのバックエンドでもストリーム
そのものです。ストリームはバッファ済みなので、読み取りがネットワークを待つことはありません。
`slurp` はストリームを閉じます。その後の読み取りは、Ring の Jetty アダプターと同じく
ボディの終端を返します。

## レスポンスマップ

| キー | 値 |
|---|---|
| `:status` | 整数。なければ 200 |
| `:headers` | ヘッダー名（文字列かキーワード）から、文字列、または 1 要素ずつ別のヘッダー行で送る文字列の seq へのマップ |
| `:body` | 文字列、各要素を `str` で送る seq、リーダーか入力ストリーム（終端まで読んで閉じる）、`java.io.File`（そのバイト列をそのまま送る）、または `nil` |

ハンドラがマップ以外を返した場合と、それ以外の種類のボディを返した場合はエラーを通知し、
トランスポートは 500 を返します。プログラムが読めるファイルを指さない `java.io.File` も
同じです。WebAssembly の配信トランスポート（`wasmtime serve`、リアクター）にはファイル
システムがないので、ファイルを配信できるのはインタープリター、JVM、war です。プログラムが
持ち運ぶ[リソース](clojure-java-io.md)の上のバイトストリームはどこでも配信できます。
レスポンスを組み立てる関数（`file-response` と `resource-response` を含む）とパラメーターの
ミドルウェアは、組み込みの [Ring ユーティリティ](ring-util.md)にあります。

## Cloudflare Workers

Worker は `handle-request` エクスポートを呼ぶので、リアクタービルドはソースを変えずに
Worker としてデプロイできます。`--emit-js-glue` が JavaScript 側をモジュールの隣に書き出します。

```console
$ rontolisp app.clj -o src/worker.wasm --no-wasi --optimize=size --emit-js-glue
$ cat src/index.js
import module from "./worker.wasm";
import { worker } from "./worker.js";

export default worker(module);
$ npx wrangler dev      # http://localhost:8787
$ npx wrangler deploy
```

ここでは `:port` と `:host` は読まれません。`wrangler.jsonc` と、他のターゲットと同じ Ring の例を
ビルドする `build.sh` を含む完全なプロジェクトは
[`examples/cloudflare-workers/ring-hello-one-source/`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/ring-hello-one-source)
にあります。
