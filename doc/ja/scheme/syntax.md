# 構文

- **リーダー**（大文字小文字を区別）: `#t` `#f` `#true` `#false`、整数、小数、有理数、
  `#x` `#b` `#o` `#d`、`#\a` `#\space` `#\newline` `#\x41`、
  `\n \t \" \\ \xHH;` を含む文字列、`#( )` ベクタ、ドット対、`'` `` ` `` `,` `,@`、`;`、`#;`、
  `#| |#`、`#!fold-case` / `#!no-fold-case`（ファイル単位。識別子と文字名を畳み込む
  -- 文字列や文字そのものは対象外 -- 対になる指示子かファイル末尾まで有効）。
- **特殊形式**: リファレンスの[構文](reference/syntax.md)に 1 つずつページがあります
  （`delay` と `delay-force` は [(scheme lazy)](reference/library-lazy.md)）。および
  `(import (scheme base) (scheme write) (scheme read) (scheme inexact) (scheme cxr) (scheme lazy)
  (scheme process-context) (scheme eval) (scheme repl))`（`only` / `except` /
  `prefix` / `rename` 可）。

9 つのライブラリそれぞれが何をエクスポートするかは[ライブラリ](libraries.md)を、
`import` を一切書かない場合に見える名前は
[*Structure and Interpretation of Computer Programs*（SICP）互換](sicp.md)を参照してください。
