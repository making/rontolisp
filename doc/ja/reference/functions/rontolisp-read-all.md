# rontolisp:read-all

`(rontolisp:read-all stream)`

非同期ストリームの残りのチャンクを **1 つの文字列** に読み切った値で確定する
future を返します。文字列チャンク (ゲストが作ったストリーム) は連結され、
オクテットチャンク -- HTTP ボディストリーム、すなわち fetch の応答の `:body` と
サーブされたリクエストの `:raw-body` が返す `(unsigned-byte 8)` ベクタ -- は
結合したうえで UTF-8 としてデコードされるため、ドキュメントを読む側は
バイトストリームからテキストを受け取ります。両方の種類が混在するストリームは
エラーです。future はストリームが終端に達した時点で確定するため、生産側は
いずれ [`rontolisp:stream-close`](rontolisp-stream-close.md) を呼ぶ必要が
あります。

```lisp
(let ((s (rontolisp:make-stream)))
  (rontolisp:stream-write s "hello ")
  (rontolisp:stream-write s "world")
  (rontolisp:stream-close s)
  (rontolisp:await (rontolisp:read-all s)))   ; => "hello world"
```

[`rontolisp:fetch`](rontolisp-fetch.md) のレスポンスボディを読み切る
イディオムです:

```console
(let ((r (rontolisp:await (rontolisp:fetch "https://example.com"))))
  (rontolisp:await (rontolisp:read-all (getf r :body))))
```

チャンクを 1 つずつ取り出すには
[`rontolisp:stream-read`](rontolisp-stream-read.md) を使ってください。読まずに
中継するなら、ストリームそのものをレスポンスボディとして返します --
トランスポートがバイト単位で正確に読み切り、途中で何もデコードしません。

**文字列**はそのまま素通りします (future は文字列そのもので確定します)。
すでに全体が到着しているボディはそれ自体が読み切った値なので、上記の 1 つの
読み切りイディオムが `:body` の形によらずそのまま動きます。

## バックエンドのサポート

非同期ストリームはインタプリタ、JVM バックエンド、そして — `rontolisp:fetch` /
`rontolisp:http-handler` が生成するリクエスト／レスポンスボディのストリーム
については — `--component` WASM バックエンドに存在します。Preview 1 WASM
モジュールがストリーム値を持てるのは、ホスト由来のボディが与えた場合だけです。
ストリームが存在しえないモジュールでは `rontolisp:streamp` は `nil` を返し、
`rontolisp:stream-read` / `rontolisp:stream-close` は呼び出し時にエラーを
シグナルします。
