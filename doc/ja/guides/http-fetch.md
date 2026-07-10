# HTTPリクエスト（fetch）

`rontolisp` パッケージはJavaScriptの `fetch` APIをモデルにした外向きHTTPと、
それと自然に組み合わせられるJSON関数を提供します。いずれも Common Lisp の
一部ではないため、`rontolisp:` 修飾子で参照します
([パッケージ](../reference/packages.md)を参照)。`rontolisp:fetch` は
リクエストを開始して即座に **プロミス** を返します。汎用のプロミス操作が
それを解決・変換し、`rontolisp:json-parse` / `rontolisp:json-stringify` が
JSONドキュメントとLispの値を相互変換します。

| 関数 | 用途 |
|----------|---------|
| [`rontolisp:fetch`](../reference/functions/rontolisp-fetch.md) | HTTPリクエストを開始する: `(rontolisp:fetch url &optional options)` |
| [`rontolisp:await`](../reference/functions/rontolisp-await.md) | プロミスが確定するまでブロックして値を返す |
| [`rontolisp:then`](../reference/functions/rontolisp-then.md) | 確定値にコールバックを適用する新しいプロミスを導出する |
| [`rontolisp:promisep`](../reference/functions/rontolisp-promisep.md) | 値がプロミスなら `t` |
| [`rontolisp:json-parse`](../reference/functions/rontolisp-json-parse.md) | JSON文字列をLispの値にパースする |
| [`rontolisp:json-stringify`](../reference/functions/rontolisp-json-stringify.md) | Lispの値をJSON文字列にシリアライズする |

> **バックエンドのサポート。** インタプリタとJVMコンパイル済みクラスはJDKの
> `java.net.http.HttpClient` を使い、`fetch` が返った瞬間からリクエストは
> バックグラウンドスレッドで走ります。WASMバックエンドは **componentモード
> 専用** です (`--component`。`wasi:http@0.2` をimportするハイブリッド):
> `fetch` はPreview 1 (コアモジュール) モードではコンパイルエラーになり、
> fetchを使うcomponentは非同期フラグに加えて `-S http=y` を付けて実行する
> 必要があります。**ブラウザプレイグラウンド** では本物のブラウザの
> `fetch()` が実行され (CORSの制約を受けます)、その間プログラムは続行
> します。プロミス操作 (`await` / `then` / `promisep`) とJSON関数は
> **すべての** バックエンド・すべてのWASMモードで動作します — 制限が
> あるのは `fetch` 自体だけです。

## 最初のリクエスト

`fetch` はリクエストが飛び始めたらすぐに返ります。プロミスを
`rontolisp:await` に渡すとレスポンスの到着までブロックし、結果の
プロパティリスト `(:status <integer> :body <string> :headers <alist>)` が
得られます:

```lisp
(let ((p (rontolisp:fetch "https://httpbin.org/get")))
  (getf (rontolisp:await p) :status))   ; => 200
```

個々のフィールドの読み取り:

```console
(let ((res (rontolisp:await (rontolisp:fetch "http://example.com/"))))
  (print (getf res :status))    ; => 200
  (print (getf res :body))      ; => "<html>...</html>"
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
インタプリタとJVMはエラーをシグナルし、WASMは `nil` を返します) は
[fetch](../reference/functions/rontolisp-fetch.md)
のリファレンスページを参照してください。

## プロミス

`fetch` が返った時点でリクエストは既に走っているので、複数のリクエストは
オーバーラップします — 全部開始してからそれぞれをawaitします:

```console
(let ((p1 (rontolisp:fetch "http://example.com/a"))
      (p2 (rontolisp:fetch "http://example.com/b")))  ; both requests running
  (list (rontolisp:await p1) (rontolisp:await p2)))
```

`rontolisp:then` は確定値を変換する新しいプロミスを導出します。JavaScriptの
`Promise.prototype.then` と同様に呼び出しはチェーンでき、プロミスを返す
コールバックは平坦化されます:

```lisp
(rontolisp:await
 (rontolisp:then (rontolisp:fetch "https://httpbin.org/get")
                 (lambda (r) (getf r :status))))   ; => 200
```

どちらの操作も汎用です: `await` はプロミス以外の値をそのまま通し、`then` も
プロミス以外を受け付けるので、プロミスかもしれない値を一様に扱えます。
`rontolisp:promisep` で両者を見分けられます:

```lisp
(rontolisp:await 42)   ; => 42
```

```lisp
(rontolisp:promisep (rontolisp:then 1 (lambda (x) x)))   ; => t
```

`then` のコールバックは最初の `await` 時に遅延実行され、結果はメモ化され
ます (確定したプロミスは何度でもawaitできます)。正確なタイミングは
[then](../reference/functions/rontolisp-then.md)
のリファレンスページを参照してください。

## JSONの扱い

`rontolisp:json-parse` はJSONドキュメントをLispの値に変換します。デフォルト
ではJSONオブジェクトはキーワードをキーとするプロパティリストになるので、
結果は `getf` で読めます。配列はリストに、`true`/`false`/`null` は
`t`/`nil` になります:

```lisp
(rontolisp:json-parse "{\"name\": \"rontolisp\", \"n\": 2}")   ; => (:name "rontolisp" :n 2)
```

```lisp
(getf (rontolisp:json-parse "{\"a\": {\"b\": [1, true, null]}}") :a)   ; => (:b (1 t nil))
```

`:hash-table` を渡すと、代わりに文字列キーのハッシュテーブルが返ります —
キーが任意の文字列のときや、空オブジェクトを `nil` と区別し続けたいときに
使ってください:

```lisp
(let ((h (rontolisp:json-parse "{\"content-type\": \"text/html\"}" :hash-table)))
  (gethash "content-type" h))   ; => "text/html"
```

`rontolisp:json-stringify` はその逆です: キーワードのプロパティリストと
ハッシュテーブルはオブジェクトに、その他のリストは配列にシリアライズ
されます:

```lisp
(rontolisp:json-stringify (list :name "rontolisp" :ok t :ver 1.5))   ; => "{\"name\":\"rontolisp\",\"ok\":true,\"ver\":1.5}"
```

```lisp
(rontolisp:json-stringify (list 1 (list 2 3) nil))   ; => "[1,[2,3],null]"
```

どちらの関数もrontolisp自身で書かれていて、使われたときにプログラムに
コンパイルされ、すべてのバックエンドで動作します。値の対応表と
エッジケース (整数の桁数、`nil` の曖昧さ、キーの順序) の全体は
[json-parse](../reference/functions/rontolisp-json-parse.md) と
[json-stringify](../reference/functions/rontolisp-json-stringify.md)
のリファレンスページにあります。

## 完全なプログラム

これらの部品を組み合わせると、JSON APIの典型的な往復になります:
`json-stringify` でリクエストボディを作り、POSTし、レスポンスをawaitして
ボディを `json-parse` でパースします。以下を `fetch-post.lisp` として保存
してください:

```console
(let* ((payload (rontolisp:json-stringify (list :name "rontolisp" :stars 1)))
       (res (rontolisp:await
             (rontolisp:fetch "https://httpbin.org/post"
                              (list :method "POST"
                                    :headers (list (cons "Content-Type" "application/json"))
                                    :body payload))))
       (json (rontolisp:json-parse (getf res :body))))
  (print (getf res :status))
  (write-line (getf json :data)))
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
wasmtime run -W gc=y -W component-model-more-async-builtins=y -S http=y fetch-post.wasm
```

HTTPではなく素のTCPを使う場合 — あるいは *サーバー* 側を実装する場合 —
は [TCPソケットガイド](tcp-sockets.md)を参照してください。
