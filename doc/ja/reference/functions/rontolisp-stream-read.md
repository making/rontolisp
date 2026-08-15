# rontolisp:stream-read

`(rontolisp:stream-read stream)`

ストリームの次のチャンクで確定する future を返します。ストリームがクローズ済みで
読み切られていれば `NIL` で確定します (ストリームの終端)。チャンクが `NIL` に
なることはないため、`NIL` は常に終端を意味します。オープン状態の空のストリームへの
read は書き込みが来るまで未確定のままです — これが await 中の非同期関数が
パークするサスペンドです。

チャンクは生産側が書いたそのものです。ゲストが作ったストリームなら文字列、
HTTP ボディストリーム (fetch の応答の `:body`、サーブされたリクエストの
`:raw-body`) なら `(unsigned-byte 8)` ベクタ — ワイヤから来たオクテットその
ままなので、レスポンスボディとして中継したボディはバイト単位で正確に渡ります。
テキストにデコードして読み切るのが [`rontolisp:read-all`](rontolisp-read-all.md)
です。

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

残りのチャンクを 1 回の await で 1 つの文字列に読み切るには
[`rontolisp:read-all`](rontolisp-read-all.md) を使ってください。

## バックエンドのサポート

非同期ストリームはインタプリタ、JVM バックエンド、そして — `rontolisp:fetch` /
`rontolisp:http-handler` が生成するリクエスト／レスポンスボディのストリーム
については — `--component` WASM バックエンドに存在します。Preview 1 WASM
モジュールがストリーム値を持てるのは、ホスト由来のボディが与えた場合だけです。
ストリームが存在しえないモジュールでは `rontolisp:streamp` は `nil` を返し、
`rontolisp:stream-read` / `rontolisp:stream-close` は呼び出し時にエラーを
シグナルします。
