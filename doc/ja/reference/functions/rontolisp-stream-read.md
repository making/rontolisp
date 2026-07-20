# rontolisp:stream-read

`(rontolisp:stream-read stream)`

ストリームの次のチャンクで確定する future を返します。ストリームがクローズ済みで
読み切られていれば `NIL` で確定します (ストリームの終端)。チャンクが `NIL` に
なることはないため、`NIL` は常に終端を意味します。オープン状態の空のストリームへの
read は書き込みが来るまで未確定のままです — これが await 中の非同期関数が
パークするサスペンドです。

```lisp
(let ((s (rontolisp:make-stream)))
  (rontolisp:stream-write s "a")
  (rontolisp:stream-close s)
  (print (rontolisp:await (rontolisp:stream-read s)))
  (print (rontolisp:await (rontolisp:stream-read s))))
```

```
"a"
NIL
```

残りの文字列チャンクを 1 回の await で連結するには
[`rontolisp:read-all`](rontolisp-read-all.md) を使ってください。

## バックエンドのサポート

非同期ストリームはインタプリタ、JVM バックエンド、そして — `rontolisp:fetch` /
`rontolisp:http-handler` が生成するリクエスト／レスポンスボディのストリーム
については — `--component` WASM バックエンドに存在します。Preview 1 WASM は
ストリーム操作をコンパイル時に拒否します。
