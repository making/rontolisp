# リファレンス

Scheme フロントエンドが提供する名前ごとの 1 ページです。手続き、定数、構文キーワードのすべてを、
それをエクスポートするライブラリごとにまとめています。**表の各名前は専用のページにリンクしており**、
そこにシグネチャ、動作、R7RS との差異のすべて、検証済みの例があります。

| ページ | 内容 |
|---|---|
| [構文](reference/syntax.md) | `(scheme base)` の構文キーワードと `import` |
| [(scheme base)](reference/library-base.md) | 数値、ペアとリスト、シンボル、文字、文字列、ベクタ、制御、例外、ポート、入出力 |
| [(scheme write)](reference/library-write.md) | `display`、`write` とその変種 |
| [(scheme read)](reference/library-read.md) | `read` |
| [(scheme char)](reference/library-char.md) | 文字の分類、大文字・小文字の変換、`char-ci=?` と `string-ci=?` とその順序比較 |
| [(scheme inexact)](reference/library-inexact.md) | 超越関数、`finite?`、`infinite?`、`nan?` |
| [(scheme cxr)](reference/library-cxr.md) | 3 段と 4 段の `car`/`cdr` の合成 |
| [(scheme lazy)](reference/library-lazy.md) | プロミス |
| [(scheme case-lambda)](reference/library-case-lambda.md) | `case-lambda` |
| [(scheme process-context)](reference/library-process-context.md) | `exit`、`emergency-exit` |
| [(scheme eval)](reference/library-eval.md) | `eval`、`environment` |
| [(scheme repl)](reference/library-repl.md) | `interaction-environment` |
| [(scheme r5rs)](reference/library-r5rs.md) | ほかのライブラリにない R5RS の名前。`import` なしで見える |
| [SICP 互換の名前](reference/library-sicp.md) | *[Structure and Interpretation of Computer Programs](sicp.md)*（SICP）/ MIT Scheme の名前。`import` なしで見える |

`(import ...)` で始まるファイルは、指定したライブラリだけを見ます。`import` のないファイルと
REPL からは、ここに載っているすべての名前が見えます。ただし
[`--scheme-standard r7rs`](standards.md) では R5RS と SICP の名前は見えません。
