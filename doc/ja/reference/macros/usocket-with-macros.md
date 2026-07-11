# usocket:with-client-socket usocket:with-connected-socket usocket:with-server-socket usocket:with-socket-listener

`(usocket:with-client-socket (socket-var stream-var host port &rest connect-args) body...)` --
`(usocket:with-connected-socket (var socket-form) body...)` --
`(usocket:with-server-socket (var socket-form) body...)` --
`(usocket:with-socket-listener (socket-var host port &rest listen-args) body...)`

usocket の便利マクロ群です: それぞれ本体の間だけソケットを束縛し、終了後に
閉じます。`with-client-socket` は接続し(`connect-args` は
`usocket:socket-connect` にそのまま渡ります)、さらに `stream-var` に
ソケットのストリームを束縛します(`nil` を渡すとこの束縛を省略)。
`with-socket-listener` は listen します(`listen-args` は
`usocket:socket-listen` にそのまま渡ります)。`with-connected-socket` と
`with-server-socket`(このシムではエイリアス)は `usocket:socket-accept`
呼び出しのような既存のソケットフォームをラップします。

```lisp
(usocket:with-socket-listener (listener "127.0.0.1" 0)
  (usocket:with-client-socket (client stream "127.0.0.1" (usocket:get-local-port listener))
    (write-line "ping" stream)
    (usocket:with-connected-socket (server (usocket:socket-accept listener))
      (read-line server)))) ; => "ping"
```

lite セマンティクス: rontolisp には `unwind-protect` がないため、ソケットが
閉じられるのは**正常終了時のみ**です -- 本体の中でエラーが通知されると
ハンドルはリークします(本家 usocket はどの脱出経路でも閉じます)。
`rontolisp:with-arena` と同じく組み込みマクロ展開なので、`funcall`/`apply`
に渡すことはできません。

## バックエンドごとの対応

- **インタープリタ**、**JVM**、**WASM コンポーネント**: 下地のソケット関数が
  動くところならどこでも(展開は全バックエンド共通)。
- **ブラウザプレイグラウンド**: 非対応。
