# HTTPリクエスト（fetch）

`rontolisp` パッケージはJavaScriptの `fetch` APIをモデルにした外向きHTTPと、
それと自然に組み合わせられるJSON関数を提供します。いずれも Common Lisp の
一部ではないため、`rontolisp:` 修飾子で参照します
([パッケージ](../reference/packages.md)を参照)。`rontolisp:fetch` は
リクエストを開始して即座に **future** を返します。`rontolisp:await`
([`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md)
の中、またはトップレベルで) がそれを解決し、`rontolisp:json-parse` /
`rontolisp:json-stringify` がJSONドキュメントとLispの値を相互変換します。

| 関数 | 用途 |
|----------|---------|
| [`rontolisp:fetch`](../reference/functions/rontolisp-fetch.md) | HTTPリクエストを開始する: `(rontolisp:fetch url &optional options)` |
| [`rontolisp:await`](../reference/special-forms/rontolisp-await.md) | future が確定するまでサスペンドして値を返す |
| [`rontolisp:futurep`](../reference/functions/rontolisp-futurep.md) | 値が future なら `t` |
| [`rontolisp:read-all`](../reference/functions/rontolisp-read-all.md) | ボディストリームのチャンクをひとつの文字列に読み尽くす (非同期) |
| [`rontolisp:json-parse`](../reference/functions/rontolisp-json-parse.md) | JSON文字列をLispの値にパースする |
| [`rontolisp:json-stringify`](../reference/functions/rontolisp-json-stringify.md) | Lispの値をJSON文字列にシリアライズする |

> **バックエンドのサポート。** インタプリタとJVMコンパイル済みクラスはJDKの
> `java.net.http.HttpClient` を使い、`fetch` が返った瞬間からリクエストは
> バックグラウンドスレッドで走ります。WASMバックエンドは **componentモード
> 専用** です (`--component`。非同期の `wasi:http@0.3.0` をimportします):
> `fetch` はPreview 1 (コアモジュール) モードではコンパイルエラーになり、
> fetchを使うcomponentは通常のフラグに加えて `-S http=y` を付けて実行する
> 必要があります。**ブラウザプレイグラウンド** では本物のブラウザの
> `fetch()` が実行され (CORSの制約を受けます)、その間プログラムは続行
> します。`await`、`futurep`、JSON関数は **すべての** バックエンド・
> すべてのWASMモードで動作します — 制限があるのは `fetch` 自体だけです。

## 最初のリクエスト

`fetch` はリクエストが飛び始めたらすぐに返ります。future を
`rontolisp:await` に渡すとレスポンスの到着までサスペンドし、結果の
プロパティリスト `(:status <integer> :headers <alist> :body <stream>)` が
得られます — どのバックエンドでも `:body` は非同期ストリームで、
[`rontolisp:read-all`](../reference/functions/rontolisp-read-all.md)
で読み尽くします:

```lisp
(let ((p (rontolisp:fetch "https://httpbin.ik.am/get")))
  (getf (rontolisp:await p) :status))   ; => 200
```

個々のフィールドの読み取り:

```console
(let ((res (rontolisp:await (rontolisp:fetch "http://example.com/"))))
  (print (getf res :status))    ; => 200
  (print (rontolisp:await (rontolisp:read-all (getf res :body))))
                                ; => "<html>...</html>"
  (print (getf res :headers)))  ; => (("content-type" . "text/html") ...)
```

## リクエストのオプション

省略可能な第2引数はオプションのプロパティリストで、`:method`
(文字列、デフォルト `"GET"`)、`:headers` (`(name . value)` の文字列ペアの
alist)、`:body` (文字列) を指定できます:

```console
;; GET with request headers (an alist of (name . value) string pairs)
(rontolisp:fetch "http://example.com/api"
                 (list :headers (list (cons "Accept" "application/json"))))

;; POST with a request body
(rontolisp:fetch "http://example.com/api"
                 (list :method "POST"
                       :headers (list (cons "Content-Type" "application/json"))
                       :body "{\"name\":\"rontolisp\"}"))
```

サポートされるメソッドは `GET`、`HEAD`、`POST`、`PUT`、`DELETE`、`OPTIONS`、
`PATCH` です。バックエンドごとのバリデーションのタイミングとエラー時の
挙動 (リクエストの失敗は `fetch` 時ではなく `await` 時に表面化します —
どのバックエンドもそこでエラーをシグナルし、`nil` が返るのはリクエストを
*開始*すらできなかった場合だけです) は
[fetch](../reference/functions/rontolisp-fetch.md)
のリファレンスページを参照してください。

## Future

`fetch` が返った時点でリクエストは既に走っているので、複数のリクエストは
オーバーラップします — 全部開始してからそれぞれを (どの順番でも) await
します:

```console
(let ((p1 (rontolisp:fetch "http://example.com/a"))
      (p2 (rontolisp:fetch "http://example.com/b")))  ; both requests running
  (list (rontolisp:await p1) (rontolisp:await p2)))
```

レスポンスを変換するには — あるいは任意の非同期ヘルパーを組み立てるには —
[`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md)
を定義します: 本体は最初の未確定の `await` まで即時に実行され、残りに
対する future が呼び出し元に返ります:

```console
(rontolisp:async-defun fetch-status (url)
  (getf (rontolisp:await (rontolisp:fetch url)) :status))

(rontolisp:await (fetch-status "https://httpbin.ik.am/get"))   ; => 200
```

`await` は汎用です: future 以外の値はそのまま通り、確定した future は
何度でもawaitできます。
[`rontolisp:futurep`](../reference/functions/rontolisp-futurep.md) で
future と通常の値を見分けられます:

```lisp
(rontolisp:await 42)   ; => 42
```

```lisp
(rontolisp:futurep (rontolisp:fetch "https://httpbin.ik.am/get"))   ; => t
```

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
  (rontolisp:json-stringify h))   ; => "{"name":"rontolisp"}"
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

## 完全なプログラム

これらの部品を組み合わせると、JSON APIの典型的な往復になります:
`json-stringify` でリクエストボディを作り、POSTし、レスポンスをawaitして
ボディを `json-parse` でパースします。以下を `fetch-post.lisp` として保存
してください:

```console
(let ((req (make-hash-table :test 'equal)))
  (setf (gethash "name" req) "rontolisp")
  (setf (gethash "stars" req) 1)
  (let* ((payload (rontolisp:json-stringify req))
         (res (rontolisp:await
               (rontolisp:fetch "https://httpbin.ik.am/post"
                                (list :method "POST"
                                      :headers (list (cons "Content-Type" "application/json"))
                                      :body payload))))
         (body (rontolisp:await (rontolisp:read-all (getf res :body))))
         (json (rontolisp:json-parse body)))
    (print (getf res :status))
    (write-line (gethash "data" json))))
```

```
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

HTTPではなく素のTCPを使う場合 — あるいは *サーバー* 側を実装する場合 —
は [TCPソケットガイド](tcp-sockets.md)を参照してください。
