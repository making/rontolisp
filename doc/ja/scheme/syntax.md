# 構文

- **リーダー**（大文字小文字を区別）: `#t` `#f` `#true` `#false`、整数、小数、有理数、
  `#x` `#b` `#o` `#d`、`#\a` `#\space` `#\newline` `#\x41`、
  `\n \t \" \\ \xHH;` を含む文字列、`#( )` ベクタ、ドット対、`'` `` ` `` `,` `,@`、`;`、`#;`、
  `#| |#`。
- **特殊形式**: `define`（両形式。内部定義は `letrec*`）、`define-values`、
  `lambda`、`if`、`cond`（`else`、`=>`）、`case`、`and`、`or`、`when`、`unless`、`let`、
  `let*`、`letrec`、`letrec*`、名前付き `let`、`do`、`begin`、`set!`、`quote`、`quasiquote`、
  `let-values`、`let*-values`、`define-record-type`（トップレベルのみ）、`delay`、
  `delay-force`、および
  `(import (scheme base) (scheme write) (scheme read) (scheme inexact) (scheme cxr) (scheme lazy)
  (scheme process-context) (scheme eval) (scheme repl))`（`only` / `except` /
  `prefix` / `rename` 可）。

9 つのライブラリそれぞれが何をエクスポートするかは[ライブラリ](libraries.md)を、
`import` を一切書かない場合に見える名前は[SICP 互換](sicp.md)を参照してください。
