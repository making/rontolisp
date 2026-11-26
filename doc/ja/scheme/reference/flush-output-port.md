# flush-output-port

`(flush-output-port [port])`

出力ポート `port`（省略時は現在の出力ポート）がバッファに溜めているものを書き出します。文字列ポートとバイトベクタポートには書き出すものがありません。

```scheme
(display "before")
(flush-output-port)
(newline)
```

```
before
```
