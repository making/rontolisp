# rontolisp:read-all

`(rontolisp:read-all stream)`

非同期ストリームの残りの*文字列*チャンクすべての連結で確定する future を
返します (文字列以外のチャンクはエラー)。future はストリームが終端に達した
時点で確定するため、生産側はいずれ
[`rontolisp:stream-close`](rontolisp-stream-close.md) を呼ぶ必要があります。

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
[`rontolisp:stream-read`](rontolisp-stream-read.md) を使ってください。

## バックエンドのサポート

非同期ストリームは現在インタプリタと JVM バックエンドに存在します。WASM
バックエンドはストリーム操作をコンパイル時に拒否します。
