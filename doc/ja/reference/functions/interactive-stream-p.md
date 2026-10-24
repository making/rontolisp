# interactive-stream-p

`(interactive-stream-p stream)`

ストリームが端末に接続されているかどうかを返します。プロセスの標準ストリームは、ホストが報告する状態をそのまま返します。`*error-output*` は標準エラー出力が端末のとき真です。`t`（既定の `*standard-output*`、`*standard-input*`、`*terminal-io*`）は、標準入力と標準出力の両方が端末のとき真です。シノニムストリームは、その変数が保持するストリームの結果を返します。それ以外のストリーム（文字列、ファイル、ソケット、合成ストリーム）は nil です。引数はストリーム指定子ではなくストリームそのもので、それ以外は `type-error` になります。

| バックエンド | 判定方法 |
|--------------|----------|
| インタプリタ、JVM | FFM 経由の `isatty`。標準入力と標準出力は、プログラムがプロセス自身のものを使っている間だけ判定し、出力を取り込んでいる実行では nil です。ネイティブアクセスが無効な場合（`--enable-native-access=ALL-UNNAMED` なしの `java Prog`。実行可能 jar とコンパイルした jar は有効にしています）は nil を返し、警告は出しません。 |
| WASM（Preview 1、ネイティブ実行ファイル） | `fd_fdstat_get` で文字デバイスなら端末とみなします。 |
| WASM コンポーネント | `wasi:cli` の `get-terminal-stdin`、`get-terminal-stdout`、`get-terminal-stderr`。 |
| `--no-wasi`、サーバーコンポーネント、ブラウザのプレイグラウンド | 常に nil です。判定する端末がありません。 |

端末では出力に色を付け、パイプやファイルにはプレーンテキストを書くプログラムの例です。

```lisp
(if (interactive-stream-p *error-output*)
    (format *error-output* "~c[31merror~c[0m~%" (code-char 27) (code-char 27))
    (format *error-output* "error~%"))
```

```lisp
(with-output-to-string (s) (princ (interactive-stream-p s) s)) ; => "NIL"
```
