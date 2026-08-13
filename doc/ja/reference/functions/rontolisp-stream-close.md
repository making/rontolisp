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
  (rontolisp:stream-close s))   ; => NIL
```

## バックエンドのサポート

非同期ストリームはインタプリタ、JVM バックエンド、そして — `rontolisp:fetch` /
`rontolisp:http-handler` が生成するリクエスト／レスポンスボディのストリーム
については — `--component` WASM バックエンドに存在します。Preview 1 WASM
モジュールがストリーム値を持てるのは、ホスト由来のボディが与えた場合だけです。
ストリームが存在しえないモジュールでは `rontolisp:streamp` は `nil` を返し、
`rontolisp:stream-read` / `rontolisp:stream-close` は呼び出し時にエラーを
シグナルします。
