# rontolisp:stream-close

`(rontolisp:stream-close stream)`

非同期ストリームの書き側をクローズし、`nil` を返します。バッファ済みのチャンクは
読み取り可能なままで、読み切った後は
[`rontolisp:stream-read`](rontolisp-stream-read.md) がストリームの終端 (`nil`)
を観測します。クローズ済みのストリームを再度クローズしても何も起きません。
クローズ後の [`rontolisp:stream-write`](rontolisp-stream-write.md) はエラーを
シグナルします。

```lisp
(let ((s (rontolisp:make-stream)))
  (rontolisp:stream-write s "x")
  (rontolisp:stream-close s)
  (rontolisp:stream-close s))   ; => nil
```

## バックエンドのサポート

非同期ストリームはインタプリタ、JVM バックエンド、そして — `rontolisp:fetch` /
`rontolisp:http-handler` が生成するリクエスト／レスポンスボディのストリーム
については — `--component` WASM バックエンドに存在します。Preview 1 WASM は
ストリーム操作をコンパイル時に拒否します。
