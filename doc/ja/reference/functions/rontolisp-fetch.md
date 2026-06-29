# rontolisp:fetch

`(rontolisp:fetch url &optional options)`

JavaScript の `fetch` API を模した送信 HTTP リクエストを行い、プロパティリスト
`(:status <integer> :body <string> :headers <alist>)` を返します。`:headers` は
レスポンスヘッダの `(name . value)` ペアの連想リストです。

```lisp
(let ((res (rontolisp:fetch "https://httpbin.org/get")))
  (getf res :status))   ; => 200
```

## オプション

省略可能な第 2 引数はオプションのプロパティリストです。認識されるキーは次のとおりです。

- `:method` — HTTP メソッドを文字列で指定します (デフォルトは `"GET"`)。サポート
  されるメソッドは `GET`、`HEAD`、`POST`、`PUT`、`DELETE`、`OPTIONS`、`PATCH` で、
  大文字小文字を区別せずに照合されます。それ以外のメソッドはエラーです。
- `:headers` — リクエストヘッダ。`(name . value)` の文字列ペアの連想リストです。
- `:body` — リクエストボディを文字列で指定します (ボディがなければ省略します)。

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

## 結果

結果はプロパティリスト `(:status <integer> :body <string> :headers
<alist>)` です。`:headers` はレスポンスヘッダの `(name . value)` ペアの連想
リストです。

```console
(let ((res (rontolisp:fetch "http://example.com/")))
  (print (getf res :status))    ; => 200
  (print (getf res :body))      ; => "<html>...</html>"
  (print (getf res :headers)))  ; => (("content-type" . "text/html") ...)
```

## バックエンドのサポート

- **インタプリタ** および **JVM**: JDK の `java.net.http.HttpClient` を使用します。
- **WASM**: コンポーネント専用で、**ハイブリッド** です。ベースの I/O は WASI 0.3
  ですが、fetch は `wasi:http@0.2` + `wasi:io@0.2` をインポートします (非同期の
  `wasi:http@0.3` はまだ上流に存在しません。`.todo/02-upgrade-fetch-to-wasi-http-0.3.md`
  を参照)。`--component` でコンパイルし、非同期フラグに加えて `-S http=y` を付けて
  実行してください。ホストの `wasi:http` を持たない Preview 1 (コアモジュール) モード
  ではコンパイルエラーのままです。

## 制限事項

- メソッドは `GET`、`HEAD`、`POST`、`PUT`、`DELETE`、`OPTIONS`、`PATCH` のいずれかで
  なければなりません。サポートされない `:method` はエラーです。インタプリタと JVM は
  実行時に拒否します。WASM バックエンドはメソッドを静的に解決し、静的に判明している
  サポート外の `:method` をコンパイル時に拒否します (実行時に計算されるメソッドはそこで
  チェックできないため GET として扱われます。一方で実行時に計算される `:body` は通常
  どおり送信されます)。
- 失敗したリクエスト (例えば接続拒否) は、インタプリタと JVM ではエラーを発生させ、
  WASM では `nil` を返します。
- WASM では、レスポンスボディには上限 (約 576 KiB) があり、非常に大きなプログラムは
  レスポンスバッファが再利用する共有リニアメモリを使い果たす可能性があります。
