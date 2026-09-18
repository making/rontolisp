# 規格

`--scheme-standard` は、プログラムのすべての Scheme ファイルをどの規格で読むかを選びます:
エントリファイル、そこからロードするファイル（実行時のロードも `-o` によるインライン化も）、
そして REPL です。

| 値 | 意味 |
|---|---|
| `rontolisp`（既定） | この処理系独自の方言: R7RS に [*Structure and Interpretation of Computer Programs*（SICP）互換](sicp.md)の名前と R5RS の名前を加えたもの。`(import ...)` のないファイルと REPL から見える。 |
| `r7rs` | R7RS-small を厳密に。ただしこのフロントエンドが実装している範囲の中で。 |

`r7rs` では:

- プログラムは `(import ...)` で始まらなければなりません。
- SICP 互換の名前と R5RS の名前は、`eval` からも含めて一切見えません。
- ファイル中で import した名前を再定義したり `set!` したりするとエラーです。REPL では再定義できます。
- `eval` は環境引数が必須です。

正しい R7RS プログラムは、どちらの値でも同じ出力になります。

```console
$ rontolisp --scheme-standard r7rs sicp.scm
error: sicp.scm:1:1: an R7RS program begins with an import declaration
```
