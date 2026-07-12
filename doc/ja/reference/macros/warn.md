# warn

`(warn datum args...)`

標準エラーストリームへ `WARNING:` メッセージを出力し、`nil` を返します。実行は継続します。[`error`](error.md) と同じコンディション designator を受け付けます: リテラルの制御文字列(`format` と同じディレクティブ)、initarg 付きのクォートされたコンディション型シンボル(`define-condition` の `:report` がメッセージになり、なければ `Condition (type initargs...) was signalled.` 形)、またはコンディションオブジェクト。lite: 警告をハンドルしたり抑制したりはできません(`muffle-warning` リスタートはなく、`handler-case` が捕捉するのはエラーと `signal` であって `warn` ではありません)。`error` と同様、`warn` は関数値を持たないマクロなので `#'warn` は未サポートです。メッセージは WASM の `--component` 出力を含むすべてのバックエンドで標準エラーへ出力されます (WASI 0.3 アダプタが fd 2 を `wasi:cli/stderr` に配線します)。

メッセージは標準出力ではなく標準エラーに出力されるため、実行可能な例ではなく静的な例として示します:

```console
(warn "unexpected value: ~a" x)
```

この呼び出しは (`x` = 42 のとき) `WARNING: unexpected value: 42` を標準エラーに出力し、`nil` に評価されます。
