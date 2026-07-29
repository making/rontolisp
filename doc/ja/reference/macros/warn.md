# warn

`(warn datum args...)`

標準エラーストリームへ `WARNING:` メッセージを出力し、`nil` を返します。実行は継続します。[`error`](error.md) と同じコンディション designator を受け付けます: リテラルの制御文字列(`format` と同じディレクティブ)、initarg 付きのクォートされたコンディション型シンボル(クラスの `:report`(定義がなければ祖先から継承したもの)がメッセージになります。他にレポートを持たない `simple-warning` のサブタイプでは `:format-control` を `:format-arguments` に適用した `format` の結果、どこにもレポートがなければ `Condition (type initargs...) was signalled.` 形)、またはコンディションオブジェクト。`warning` に対する [`handler-bind`](handler-bind.md) ハンドラはメッセージが印字される前にシグナル点で実行され、[`muffle-warning`](../functions/muffle-warning.md) を呼んで出力を中止できます(`warn` は静かに `nil` を返します)。`handler-case` が捕捉するのはエラーと `signal` であって `warn` ではありません。`error` と同様、`warn` は関数値を持ちます: インタプリタでは `#'warn` を通しても完全な designator プロトコルが有効で、コンパイル系バックエンドは datum のみを転送します。メッセージは WASM の `--component` 出力を含むすべてのバックエンドで標準エラーへ出力されます (WASI 0.3 アダプタが fd 2 を `wasi:cli/stderr` に配線します)。

メッセージは標準出力ではなく標準エラーに出力されるため、実行可能な例ではなく静的な例として示します:

```console
(warn "unexpected value: ~a" x)
```

この呼び出しは (`x` = 42 のとき) `WARNING: unexpected value: 42` を標準エラーに出力し、`nil` に評価されます。
