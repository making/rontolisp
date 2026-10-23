# ネイティブ実行ファイルへのコンパイル

`-o` とともに `--native` を指定すると、プログラムを自己完結した実行ファイル 1 つにコンパイルします: [WASM](wasm.md) 出力を wasmtime でマシンコードへプリコンパイルし、小さなランナーの後ろに付加したものです。実行に wasmtime も JVM も必要ありません:

```bash
echo '(print (+ 1 2))' > hello.lisp
rontolisp hello.lisp --native -o hello
./hello
```

```lisp
(print (+ 1 2))
```

```
3
```

実行ファイルの中身は `-o hello.wasm` が書き出す WASI コマンドモジュールで、`wasmtime run` と同じように実行されます: 標準出力も終了ステータスも同じです -- プログラム自身の `(uiop:quit n)` のコード、正常に戻れば 0、捕捉されないエラーの後は 134。引数はプログラムの `(uiop:command-line-arguments)` になり、プロセスの環境変数と標準入力も見えます。それ以外のファイルは書き出されません: `.wasm` とそのプリコンパイル結果はメモリ上にしか存在しません。

## ファイル

カレントディレクトリと `/` がプログラムに開かれているので、相対パス(`../x` を含む)も絶対パスも通常のネイティブプログラムと同じように使えます。

## HTTP

実行ファイルでも [`rontolisp:fetch`](../guides/http-fetch.md) はインタプリタや JVM と同じように動きます: `fetch` が返った時点でリクエストは送信中で、複数のリクエストは並行して進み、`await` は `(:status :headers :body)` を返します(`:body` は応答のオクテットのストリームです)。失敗したリクエスト(接続の拒否や、信頼するルート証明書のどれも保証しない証明書)は `await` でエラーをシグナルします。リクエストはランナー自身が HTTP/1.1 で、1 リクエストにつき 1 接続を使って送ります。JDK のクライアントと同じく、リダイレクトはたどらず、圧縮された応答も展開しません。

HTTPS は実行ファイルに組み込まれた Mozilla のルート証明書を信頼するので、マシン上の証明書バンドルは要りません。社内の認証局や TLS を検査するプロキシの認証局を信頼させたいときは、`SSL_CERT_FILE` に PEM ファイルを指定すると、組み込みのルート証明書の代わりにそのファイルの証明書を信頼します:

```bash
SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt ./fetcher
```

fetch するプログラムは、HTTP と TLS を含む大きいランナーから始まります。それ以外のプログラムは小さいランナーのままです([サイズと速度](#size-and-speed))。

## HTTP の配信

`(clack:clackup app :server :rontolisp)` で起動した [Clack](../guides/clack.md) アプリケーション、`run-server` で起動した [Ring](../clojure/reference/ring.md) ハンドラ、[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md) で配信するハンドラは、実行ファイルから配信できます: ランナーが `:port` と `:address` にバインドして接続を受け付け、HTTP/1.1 を話し、リクエストにはプログラムが応答します。

```bash
rontolisp app.lisp --native -o app
./app        # 止めるまで配信する(Ctrl-C)
```

ランナーは接続をそれぞれ専用のスレッドで読み(リクエストのヘッダーとボディ全体。ボディは `Content-Length` でも chunked でもかまいません)、HTTP/1.1 のとおり接続を keep-alive し、1 分間何も来ない接続を閉じます。アプリケーションはプログラムの唯一のスレッドで 1 リクエストずつ応答し、レスポンスは `Content-Length` 付きでまとめて送られます。そのため `clackup`、`run-server`、`http-handler` は戻りません: `:use-thread` や `:join? false` があってもプログラムを先へ進める別のスレッドがなく、サーバーはプロセスとともに止まります。リクエストはインタプリタや JVM が組み立てるものと同じで、相手のアドレスとポートも入ります。ハンドラが配信するファイルは、ほかのプログラムと同じくファイルシステムから読みます([ファイル](#files))。

## フラグ

デフォルトの WASM 出力のフラグはすべて使えます(`--simd`、`--optimize`、`--dynamic` など)。別の種類のモジュールを求めるフラグは名前を挙げて拒否されます: `--component`、`--no-wasi`、`--no-gc`、`--host-random`、`--host-fetch`、`--host-boundary`、`--reentrant`、`--emit-js-glue`。`.wasm`、`.class`、`.jar`、`.war` で終わる `-o` の名前も同様です。fetch や配信をするプログラムにフラグは要りません。リクエストの送信も配信もランナー自身が行います([HTTP](#http)、[HTTP の配信](#serving-http))。

## 動作環境

実行ファイルはデフォルトでコンパイラを動かしているプラットフォーム(Linux または macOS、x86_64 または aarch64)向けに作られ、そのプラットフォームのすべてのプロセッサが持つ CPU 機能だけを使うので、どれほど古くても同じプラットフォームのマシンなら動きます。Linux では静的リンクされるので、同じアーキテクチャならC ライブラリを問わずどのディストリビューションでも動きます。リリースのバイナリと実行可能 JAR はプリコンパイラを含みます。ホスト向けのプリコンパイラを含まない rontolisp のビルドは `--native is not available for <os>-<arch>` と答えます(ソースからのビルド: [ビルドとインストール](../getting-started/build.md))。

`--native-target` は別のプラットフォーム向けに作ります: `linux-x86_64`、`linux-aarch64`、`macos-aarch64`。リリースのバイナリと実行可能 JAR はそれぞれのランナーを含みます。ソースからのビルドは自分のプラットフォームのランナーだけを含み、別のものを求められると含んでいるものを挙げて答えます。

```bash
rontolisp hello.lisp --native --native-target linux-aarch64 -o hello-arm64
```

`--native-cpu` はマシンコードが使ってよい CPU 機能を選びます: `baseline`(デフォルト)、`host`(コンパイルするマシンの CPU のすべての機能)、x86_64 ではレベル `x86-64-v2`、`x86-64-v3`、`x86-64-v4`。実行ファイルは、その機能のどれかを持たない CPU で起動されると実行を拒否し、欠けている機能を挙げます。ベンチマークプログラムで測ったところ、新しい機能を使っても rontolisp が生成するコードは速くならなかったので、デフォルトのままで実質的な損はありません。

プリコンパイラは共有ライブラリで、`rontolisp` が一度だけ `$XDG_CACHE_HOME/rontolisp`(なければ `~/.cache/rontolisp`、macOS では `~/Library/Caches/rontolisp`)へ展開します。システムプロパティ `rontolisp.native.cache` で場所を変えられます(`-Drontolisp.native.cache=DIR`)。

## macOS の GUI プログラム

Apple シリコン (`macos-aarch64`) では、実行ファイルは `objc`、`appkit`、`metal`、`scene` パッケージを使うプログラムも動かせます: ランナー自身が Objective-C ランタイムを呼び、プログラムは AppKit が求めるプロセスの最初のスレッドで動きます。[macOS GUI](../guides/objc-appkit.md#a-native-executable) を参照してください。それ以外のターゲットでは、そうしたプログラムは参照を挙げるコンパイルエラーになります。

## サイズと速度

実行ファイルはランナー(Linux x86_64 で 2.1 MB、Linux aarch64 で 1.9 MB、macOS で 1.7 MB)に `.wasm` の約 11 倍を足した大きさです: Linux x86_64 では `hello` で 2.1 MB、28 KB のモジュールで 2.3 MB。fetch や配信をするプログラムのランナーは Linux x86_64 で 3.3 MB、Linux aarch64 で 2.9 MB です。起動は約 10 ms で、同じモジュールを `wasmtime run` で動かすのとほぼ同じ速度で動きます。
