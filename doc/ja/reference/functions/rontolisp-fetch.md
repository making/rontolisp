# rontolisp:fetch

`(rontolisp:fetch url &optional options)`

JavaScript の `fetch` API を模した送信 HTTP リクエストを開始し、リクエストが
非同期に実行されている間に **プロミス** を即座に返します。プロミスは不透明な値で、
`#<PROMISE>` と印字され、[`rontolisp:promisep`](rontolisp-promisep.md) を満たします。
プロミスを [`rontolisp:await`](rontolisp-await.md) に渡すと、レスポンスの到着まで
ブロックし、結果のプロパティリスト
`(:status <integer> :body <string> :headers <alist>)` が得られます。
[`rontolisp:then`](rontolisp-then.md) でコールバックをチェーンすることもできます。

```lisp
(let ((p (rontolisp:fetch "https://httpbin.org/get")))
  (getf (rontolisp:await p) :status))   ; => 200
```

`fetch` が返った時点でリクエストは既に送信されているため、複数のリクエストを
並行させることができます。

```console
(let ((p1 (rontolisp:fetch "http://example.com/a"))
      (p2 (rontolisp:fetch "http://example.com/b")))  ; both requests running
  (list (rontolisp:await p1) (rontolisp:await p2)))
```

## オプション

省略可能な第 2 引数はオプションのプロパティリストです。認識されるキーは次のとおりです。

- `:method` — HTTP メソッドを文字列で指定します (デフォルトは `"GET"`)。サポート
  されるメソッドは `GET`、`HEAD`、`POST`、`PUT`、`DELETE`、`OPTIONS`、`PATCH` で、
  大文字小文字を区別せずに照合されます。それ以外のメソッドはエラーです。
- `:headers` — リクエストヘッダ。`(name . value)` の文字列ペアの連想リストです。
- `:body` — リクエストボディを文字列で指定します (ボディがなければ省略します)。

オプションは `fetch` の呼び出し時に検証されます (不正な引数に対して同期的に
例外を投げる JavaScript の `fetch` と同じです)。

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

`fetch` 自体はプロミスを返します。それを await するとプロパティリスト
`(:status <integer> :body <string> :headers <alist>)` が得られます。`:headers`
はレスポンスヘッダの `(name . value)` ペアの連想リストです。

```console
(let ((res (rontolisp:await (rontolisp:fetch "http://example.com/"))))
  (print (getf res :status))    ; => 200
  (print (getf res :body))      ; => "<html>...</html>"
  (print (getf res :headers)))  ; => (("content-type" . "text/html") ...)
```

JSON のレスポンスボディは
[`rontolisp:json-parse`](rontolisp-json-parse.md) で Lisp の値にパースでき、
[`rontolisp:json-stringify`](rontolisp-json-stringify.md) で S 式から JSON の
リクエスト `:body` を組み立てられます。

## バックエンドのサポート

- **インタプリタ** および **JVM**: JDK の `java.net.http.HttpClient` を使用します。
  `fetch` が返った瞬間からリクエストはバックグラウンドスレッドで実行されます。
- **WASM**: コンポーネント専用で、**ハイブリッド** です。ベースの I/O は WASI 0.3
  ですが、fetch は `wasi:http@0.2` + `wasi:io@0.2` をインポートします (非同期の
  `wasi:http@0.3` はまだ上流に存在しません。`.todo/02-upgrade-fetch-to-wasi-http-0.3.md`
  を参照)。プロミスは処理中の `wasi:http` レスポンスハンドルそのものなので、複数の
  リクエストが実際に並行します。`--component` でコンパイルし、非同期フラグに加えて
  `-S http=y` を付けて実行してください。ホストの `wasi:http` を持たない Preview 1
  (コアモジュール) モードでは fetch はコンパイルエラーのままです。汎用のプロミス
  操作 (`await`、`then`、`promisep`) はどのモードでもコンパイルできます。fetch は
  [`rontolisp:http-handler`](rontolisp-http-handler.md) の serve コンポーネント内
  (プロキシ型のハンドラ) でも動作します。`wasmtime serve -W gc=y -S http=y` で
  実行してください — この場合、非同期フラグは不要です。
- **ブラウザ プレイグラウンド**: 真に非同期です。インタプリタは Web Worker 内で
  実行され、`fetch` はリクエストをページのメインスレッドに引き渡します。メイン
  スレッドがブラウザの本物の `fetch()` を (CORS の制約の下で) 実行している間も
  プログラムは動き続けるためリクエストは並行し、`await` はレスポンスの到着まで
  ワーカーをブロックします。クロスオリジン分離が使えない環境
  (`SharedArrayBuffer` が無効) では、fetch ごとに同期リクエストへフォールバック
  します — プログラムの動作は同じですが、リクエストは並行しません。

## 制限事項

- メソッドは `GET`、`HEAD`、`POST`、`PUT`、`DELETE`、`OPTIONS`、`PATCH` のいずれかで
  なければなりません。サポートされない `:method` はエラーです。インタプリタと JVM は
  `fetch` の時点で拒否します。WASM バックエンドはメソッドを静的に解決し、静的に判明している
  サポート外の `:method` をコンパイル時に拒否します (実行時に計算されるメソッドはそこで
  チェックできないため GET として扱われます。一方で実行時に計算される `:body` は通常
  どおり送信されます)。
- 失敗したリクエスト (例えば接続拒否) はプロミスを await した時点で顕在化します —
  JavaScript の `await` の reject と同じタイミングです。インタプリタと JVM ではエラーを
  発生させ、WASM では `await` が `nil` を返します。WASM でリクエストの開始自体が
  できない場合 (例えば不正な URL) は `fetch` がプロミスの代わりに `nil` を返し、
  `nil` を await すると `nil` になります。
- WASM では、レスポンスボディには上限 (約 576 KiB) があり、非常に大きなプログラムは
  レスポンスバッファが再利用する共有リニアメモリを使い果たす可能性があります。
