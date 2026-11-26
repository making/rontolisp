# rontolisp:streamp

`(rontolisp:streamp value)`

`value` が*非同期*ストリーム — [`rontolisp:make-stream`](rontolisp-make-stream.md)
が返す値、または [`rontolisp:fetch`](rontolisp-fetch.md) のレスポンスボディ —
なら `t`、それ以外なら `nil` を返します。

```lisp
(rontolisp:streamp (rontolisp:make-stream))   ; => T
(rontolisp:streamp 42)                        ; => NIL
```

ファイルストリームの述語 `cl:streamp` とは別のシンボルです: 互いに相手の
ストリームには `nil` を返します。

```lisp
(streamp (rontolisp:make-stream))   ; => NIL
```

非同期ストリームは不透明な値です: リーダ構文はなく、`#<STREAM>` と印字されます。

## バックエンドのサポート

非同期ストリームはインタプリタ、JVM バックエンド、そして — `rontolisp:fetch` /
`rontolisp:http-handler` が生成するリクエスト／レスポンスボディのストリーム
については — `--component` WASM バックエンドに存在します。Preview 1 WASM
モジュールがストリーム値を持てるのは、ホスト由来のボディが与えた場合だけです。
ストリームが存在しえないモジュールでは `rontolisp:streamp` は `nil` を返し、
`rontolisp:stream-read` / `rontolisp:stream-close` は呼び出し時にエラーを
シグナルします。
