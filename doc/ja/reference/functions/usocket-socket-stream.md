# usocket:socket-stream usocket:socket-close

`(usocket:socket-stream socket)` -- `(usocket:socket-close socket)`

`socket-stream` はソケットに対応するストリームを返し、`socket-close` は
フラッシュして閉じます。このシムではソケットはストリームそのもの
(`rontolisp:tcp-*` は `open` と同じくストリーム値を返す)
なので、`socket-stream` は恒等関数です --
`(read-line (usocket:socket-stream sock))` のようなポータブルな usocket
コードがそのまま動くために存在します -- そして `socket-close` は `close`
です。

```lisp
(usocket:socket-stream 42) ; => 42
```

```console
(let ((stream (usocket:socket-stream sock)))
  (write-line "ping" stream)
  (print (read-line stream))
  (usocket:socket-close sock))
```

## バックエンドごとの対応

- **インタープリタ**、**JVM**、**WASM コンポーネント**: ソケット自体が動く
  ところならどこでも(`socket-stream` は純粋関数なのでどこでも動きます)。
- **ブラウザプレイグラウンド**: `socket-stream` は動きますが、ソケットは
  非対応です。
