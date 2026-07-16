# rontolisp:streamp

`(rontolisp:streamp value)`

`value` が*非同期*ストリーム — [`rontolisp:make-stream`](rontolisp-make-stream.md)
が返す値、または [`rontolisp:fetch`](rontolisp-fetch.md) のレスポンスボディ —
なら `t`、それ以外なら `nil` を返します。

```lisp
(rontolisp:streamp (rontolisp:make-stream))   ; => t
(rontolisp:streamp 42)                        ; => nil
```

ファイルストリームの述語 `cl:streamp` とは別のシンボルです: 互いに相手の
ストリームには `nil` を返します。

```lisp
(streamp (rontolisp:make-stream))   ; => nil
```

非同期ストリームは不透明な値です: リーダ構文はなく、`#<STREAM>` と印字されます。

## バックエンドのサポート

非同期ストリームはインタプリタ、JVM バックエンド、そして — `rontolisp:fetch` /
`rontolisp:http-handler` が生成するリクエスト／レスポンスボディのストリーム
については — `--component` WASM バックエンドに存在します。Preview 1 WASM は
ストリーム操作をコンパイル時に拒否します。
