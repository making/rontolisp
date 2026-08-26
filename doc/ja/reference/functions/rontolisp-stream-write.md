# rontolisp:stream-write

`(rontolisp:stream-write stream chunk)`

`chunk` (`nil` は不可) を非同期ストリームに追加し、ストリームが受け付けた時点で
確定する future を返します。生産側は各書き込みを
[`rontolisp:await`](../special-forms/rontolisp-await.md) することでフロー制御
できます。

```lisp
(let ((s (rontolisp:make-stream)))
  (rontolisp:await (rontolisp:stream-write s "chunk"))
  (rontolisp:stream-close s)
  (rontolisp:await (rontolisp:stream-read s)))   ; => "chunk"
```

[`rontolisp:stream-close`](rontolisp-stream-close.md) で書き側をクローズした
ストリームへの書き込みはエラーをシグナルします:

```console
CL-USER> (let ((s (rontolisp:make-stream)))
    (rontolisp:stream-close s)
    (rontolisp:stream-write s "x"))
stream-write: the stream is closed
```

## バックエンドのサポート

ゲスト側で作るストリーム (`rontolisp:make-stream` / `rontolisp:stream-write`)
は現在インタプリタと JVM バックエンドに存在します。WASM バックエンドは
コンパイル時に拒否します (`--component` プログラムのストリームは
`rontolisp:fetch` / `rontolisp:http-handler` のボディ由来です)。
