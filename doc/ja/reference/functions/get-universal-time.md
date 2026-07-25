# get-universal-time

`(get-universal-time)`

Common Lisp のエポックである 1900-01-01 GMT からの経過秒数 (Unix 時間に 2208988800 を加えた値) として現在時刻を返します。すべてのバックエンドが整数を返します。WASM バックエンドはホストから時計を読みます (Preview 1 では実際のホスト時計、`--component` モードでは `wasi:clocks@0.3.0`)。

```lisp
(get-universal-time)
```

各呼び出しはその瞬間の壁時計時間を返すため、結果は非決定的です。2 回の測定値を引くと経過秒数が得られます。
