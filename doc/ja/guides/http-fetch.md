# HTTPリクエスト（fetch）

`rontolisp` パッケージはJavaScriptの `fetch` APIをモデルにした外向きHTTPと、
それと自然に組み合わせられるJSON関数を提供します。いずれも Common Lisp の
一部ではないため、`rontolisp:` 修飾子で参照します
([パッケージ](../reference/packages.md)を参照)。`rontolisp:fetch` は
リクエストを開始して即座に **future** を返します。それは `rontolisp:await`
で解決します。future と `await` の仕組みそのものはHTTP固有ではなく —
[非同期プログラミングガイド](async.md)の主題です。このページはそれを前提と
した上で、リクエストに固有の部分だけを扱います。

| 関数 | 用途 |
|----------|---------|
| [`rontolisp:fetch`](../reference/functions/rontolisp-fetch.md) | HTTPリクエストを開始する: `(rontolisp:fetch url &optional options)` |
| [`rontolisp:read-all`](../reference/functions/rontolisp-read-all.md) | レスポンスのボディストリームをひとつの文字列に読み尽くす (非同期) |
| [`rontolisp:json-parse`](../reference/functions/rontolisp-json-parse.md) | JSON文字列をLispの値にパースする |
| [`rontolisp:json-stringify`](../reference/functions/rontolisp-json-stringify.md) | Lispの値をJSON文字列にシリアライズする |

> **バックエンドのサポート。** インタプリタとJVMコンパイル済みクラスはJDKの
> `java.net.http.HttpClient` を使い、`fetch` が返った瞬間からリクエストは
> バックグラウンドスレッドで走ります。WASMでは `fetch` は代わりに通信を
> 行えるホストを必要とし、それは **component** (`--component`。非同期の
> `wasi:http@0.3.0` をimportし、通常のフラグに加えて `-S http=y` を付けて
> 実行します) か、**`--host-fetch` 付きでビルドした `--no-wasi` リアクタ**
> のどちらかです。後者は同じソースを `env.fetch` というひとつのimport経由で
> ホスト自身のHTTPクライアントへ落とします — Cloudflare Workerやnode埋め込み
> がfetchする仕組みがこれです
> ([後述の節](#fetching-from-a-reactor---no-wasi---host-fetch))。どちらでも
> ない場合、`fetch` はPreview 1 (コアモジュール) モードではコンパイルエラーに
> なります。**ブラウザプレイグラウンド** では本物のブラウザの
> `fetch()` が実行され (CORSの制約を受けます)、その間プログラムは続行
> します。JSON関数は **すべての** バックエンド・すべてのWASMモードで動作
> します。制限があるのは `fetch` 自体だけです。`await`、`futurep`、future
> コンビネータは[非同期ガイド](async.md)で扱います。

## 最初のリクエスト

`fetch` はリクエストが飛び始めたらすぐに返ります。future を
`rontolisp:await` に渡すとレスポンスの到着までサスペンドし、結果の
プロパティリスト `(:status <integer> :headers <alist> :body <stream>)` が
得られます — どのバックエンドでも `:body` は
[非同期ストリーム](async.md#asynchronous-streams)で、
[`rontolisp:read-all`](../reference/functions/rontolisp-read-all.md)
で読み尽くします:

```lisp
(let ((p (rontolisp:fetch "https://httpbin.ik.am/get")))
  (getf (rontolisp:await p) :status))   ; => 200
```

個々のフィールドの読み取り:

```lisp
(let ((res (rontolisp:await (rontolisp:fetch "https://httpbin.ik.am/get"))))
  (print (getf res :status))    ; => 200
  (print (rontolisp:await (rontolisp:read-all (getf res :body))))
                                ; => "{...}"
  (print (getf res :headers)))  ; => (("content-type" . "application/json") ...)
```

`fetch` が返った時点でリクエストは既に走っているので、複数のリクエストは
オーバーラップします — 全部開始してからそれぞれを (どの順番でも) await
します。これは future の一般的な[オーバーラップ](async.md#overlapping-work)の
挙動そのものです:

```lisp
(let ((p1 (rontolisp:fetch "https://httpbin.ik.am/status/200"))
      (p2 (rontolisp:fetch "https://httpbin.ik.am/status/201")))  ; 両方のリクエストが並行して走る
  (list (getf (rontolisp:await p1) :status)
        (getf (rontolisp:await p2) :status)))                     ; => (200 201)
```

## リクエストのオプション

省略可能な第2引数はオプションのプロパティリストで、`:method`
(文字列、デフォルト `"GET"`)、`:headers` (`(name . value)` の文字列ペアの
alist)、`:body` (文字列) を指定できます:

```lisp
;; GETリクエスト（ヘッダーは (name . value) 文字列ペアの alist）
(rontolisp:await
  (rontolisp:fetch "https://httpbin.ik.am/get"
                   '(:headers (("Accept" . "application/json")))))

;; POSTリクエスト（ボディ付き）
(rontolisp:await
  (rontolisp:fetch "https://httpbin.ik.am/post"
                   '(:method "POST"
                     :headers (("Content-Type" . "application/json"))
                     :body "{\"name\":\"rontolisp\"}")))
```

サポートされるメソッドは `GET`、`HEAD`、`POST`、`PUT`、`DELETE`、`OPTIONS`、
`PATCH` です。バックエンドごとのバリデーションのタイミングとエラー時の
挙動 (リクエストの失敗は `fetch` 時ではなく `await` 時に表面化します —
どのバックエンドもそこでエラーをシグナルし、`nil` が返るのはリクエストを
*開始*すらできなかった場合だけです) は
[fetch](../reference/functions/rontolisp-fetch.md)
のリファレンスページを参照してください。

## JSONの扱い

`rontolisp:json-parse` はJSONドキュメントをLispの値に変換します。挙動は
[`com.inuoe.jzon`](asdf-systems.md) のデフォルトに従います: JSONオブジェクトは
文字列をキーとするハッシュテーブルに、配列はベクタになり、`true`/`false`/`null`
はそれぞれ `t`/`nil`/シンボル `null` になります:

```lisp
(gethash "name" (rontolisp:json-parse "{\"name\": \"rontolisp\", \"n\": 2}"))   ; => "rontolisp"
```

```lisp
(gethash "b" (gethash "a" (rontolisp:json-parse "{\"a\": {\"b\": [1, true, null]}}")))   ; => #(1 t null)
```

`rontolisp:json-stringify` はその逆です: ハッシュテーブルはオブジェクトに、
ベクタまたはリストは配列になり、`nil`/`t`/シンボル `null` はそれぞれ
`false`/`true`/`null` になります:

```lisp
(let ((h (make-hash-table :test 'equal)))
  (setf (gethash "name" h) "rontolisp")
  (rontolisp:json-stringify h))   ; => "{\"name\":\"rontolisp\"}"
```

```lisp
(rontolisp:json-stringify (list 1 (list 2 3) nil))   ; => "[1,[2,3],false]"
```

どちらの関数もrontolisp自身で書かれていて、すべてのバックエンドで使われた
ときにプログラムへコンパイルされます。またjzonの軽量なサブセットなので —
プログラムはそのままjzonへ切り替えられます。値の対応表の全体とエッジケース
(整数の桁数、キーの順序) は
[json-parse](../reference/functions/rontolisp-json-parse.md) と
[json-stringify](../reference/functions/rontolisp-json-stringify.md)
のリファレンスページにあります。

ハッシュテーブルを手で組み立てる — `make-hash-table` してからキーごとに
`setf gethash` する — のが面倒なときは、よくあるリスト形式との相互変換を行う
4つのユーティリティが使えます。
[`rontolisp:plist-hash-table`](../reference/functions/rontolisp-plist-hash-table.md)
と [`rontolisp:alist-hash-table`](../reference/functions/rontolisp-alist-hash-table.md)
はプロパティリストや連想リストからハッシュテーブルを作り (`:name` のような
キーワードキーは `"name"` に小文字化されます)、JSONオブジェクトをクオート
リテラルから1つの式で書けます:

```lisp
(rontolisp:json-stringify (rontolisp:plist-hash-table '(:name "rontolisp" :stars 1)))   ; => "{\"name\":\"rontolisp\",\"stars\":1}"
```

```lisp
(rontolisp:json-stringify (rontolisp:alist-hash-table '(("name" . "rontolisp") ("stars" . 1))))   ; => "{\"name\":\"rontolisp\",\"stars\":1}"
```

逆変換の
[`rontolisp:hash-table-plist`](../reference/functions/rontolisp-hash-table-plist.md)
と [`rontolisp:hash-table-alist`](../reference/functions/rontolisp-hash-table-alist.md)
は、パースしたオブジェクトを `getf` や `assoc` で辿れるリストに平坦化します
(パース結果のキーは文字列なので、`assoc` には `:test 'equal` を指定します):

```lisp
(rontolisp:hash-table-plist (rontolisp:json-parse "{\"n\": 1}"))   ; => ("n" 1)
```

```lisp
(rontolisp:hash-table-alist (rontolisp:json-parse "{\"n\": 1}"))   ; => (("n" . 1))
```

いずれも同名の `alexandria` 関数の軽量なサブセットで、JSON関数と同様に
すべてのバックエンドでプログラムにコンパイルされます。

## 完全なプログラム

これらの部品を組み合わせると、JSON APIの典型的な往復になります:
`json-stringify` でリクエストボディを作り、POSTし、レスポンスをawaitして
ボディを `json-parse` でパースします。以下を `fetch-post.lisp` として保存
してください:

```lisp
(let ((req (make-hash-table :test 'equal)))
  (setf (gethash "name" req) "rontolisp")
  (setf (gethash "stars" req) 1)
  (let* ((payload (rontolisp:json-stringify req))
         (res (rontolisp:await
               (rontolisp:fetch "https://httpbin.ik.am/post"
                                `(:method "POST"
                                  :headers (("Content-Type" . "application/json"))
                                  :body ,payload))))
         (body (rontolisp:await (rontolisp:read-all (getf res :body))))
         (json (rontolisp:json-parse body)))
    (print (getf res :status))
    (write-line (or (gethash "data" json) body))))
```

```console
200
{"name":"rontolisp","stars":1}
```

### 実行方法

インタプリタで:

```bash
rontolisp fetch-post.lisp
```

JVMクラスにコンパイルして (クラス名は出力ファイル名から付きます):

```bash
rontolisp fetch-post.lisp -o FetchPost.class
java FetchPost
```

WASM componentにコンパイルして (wasmtime 46+。外向きHTTPを許可する
`-S http=y` に注意 — これがないと `wasi:http` のimportが提供されず、
インスタンス化に失敗します):

```bash
rontolisp fetch-post.lisp -o fetch-post.wasm --component
wasmtime run -W gc=y -W exceptions=y -S http=y fetch-post.wasm
```

## リアクタからのfetch (`--no-wasi --host-fetch`)

[`--no-wasi` リアクタ](wasm-gc-module.md#no-wasi-reactor-mode)はWASIを一切
importしないため、fetchを通す `wasi:http` を持ちません — しかしリアクタを
駆動するホスト (Cloudflare Worker、node、ブラウザページ) は自前のHTTP
クライアントを持っています。`--host-fetch` は `rontolisp:fetch` をそこへ、
`env.fetch(request-json) -> response-json` というひとつの注入importとして
経路付けします:

```bash
rontolisp worker.lisp -o worker.wasm --no-wasi --host-fetch --optimize=size
```

Lisp側は何も変わりません — オプションも `(:status :headers :body)` の答えも
同一です — が、このバックエンドに固有な点が3つあります:

- **fetchはトップレベルではなくエクスポートの中に置く。** リアクタには
  `_start` がありません: ホストがインスタンス化し、エクスポートされた関数を
  呼びます。JavaScriptホストは `env.fetch` を `WebAssembly.Suspending` (JSPI)
  で実装し、これはpromiseが確定するまでwasmスタック全体を停止させますが、
  `_initialize` だけは停止できない唯一のスタックです — したがって
  *ロードパス* が到達するfetchはそこで拒否されます。ビルドはそれを名指しで
  警告します。
- **`:body` はストリームではなくeagerな文字列ひとつ**です: 呼び出しと同時に
  レスポンス全体が届いています。
  [`rontolisp:read-all`](../reference/functions/rontolisp-read-all.md) は
  それを素通しするので、上記の読み尽くしの書き方は一切変更不要です。
- **開始 == 確定。** future は `fetch` が返った時点で確定済みです (往復の間
  スタック全体が停止していたため)。したがって `await` はサスペンドせず、
  2つのfetchが重なることはなく、通信の失敗は `await` ではなく `fetch` の
  呼び出しで signal されます — Preview 1 が一貫して持つ[退化した非同期の
  形](async.md#under-the-hood-wasi-preview-3-futures--streams)です。

引き換えにホスト側がひとつ義務を負い、これもビルドが表示します:
すべてのエクスポートを `WebAssembly.promising` 経由で呼び、呼び出しを直列化
すること。サスペンドしたハンドラはイベントループに制御を返すため、2つ目の
リクエストが同じインスタンスに入るとグローバルとアロケータを共有してしまい
ます — モジュールは両方の呼び出しを壊す代わりに、その再入をトラップで拒否
します。同期的な `env.fetch` (JSPIのないnode、テストスタブ) にはこの義務は
不要で、それも同様に有効です。

典型的な形は served なリアクタです:
[`http-handler`](http-handler.md) か
[Clackアプリケーション](clack.md#a-host-that-calls-you-the-reactor-build)を
これらのフラグでコンパイルすると `handle-request` がエクスポートされ、その
ハンドラがfetchします。
[`examples/cloudflare-workers/dog-fetcher`](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/dog-fetcher)
がまさにそれで、JavaScript側も含まれています — インタプリタ・JVM・
`wasi:http` component でも動く、ひとつのソースです。

HTTPではなく素のTCPを使う場合 — あるいは *サーバー* 側を実装する場合 —
は [TCPソケットガイド](tcp-sockets.md)を参照してください。
