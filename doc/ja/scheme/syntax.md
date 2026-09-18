# 構文

- **リーダー**（大文字小文字を区別）: `#t` `#f` `#true` `#false`、整数、小数、有理数、
  `#x` `#b` `#o` `#d`、`#\a` `#\space` `#\newline` `#\x41`、
  `\n \t \" \\ \xHH;` を含む文字列、`#( )` ベクタ、ドット対、`'` `` ` `` `,` `,@`、`;`、`#;`、
  `#| |#`、`#!fold-case` / `#!no-fold-case`（ファイル単位。識別子と文字名を畳み込む
  -- 文字列や文字そのものは対象外 -- 対になる指示子かファイル末尾まで有効）。
- **特殊形式**: `define`（両形式。内部定義は `letrec*`）、`define-values`、
  `lambda`、`if`、`cond`（`else`、`=>`）、`case`、`and`、`or`、`when`、`unless`、`let`、
  `let*`、`letrec`、`letrec*`、名前付き `let`、`do`、`begin`、`set!`、`quote`、`quasiquote`、
  `let-values`、`let*-values`、`define-record-type`（トップレベルのみ）、`delay`、
  `delay-force`、`define-syntax` / `let-syntax` / `letrec-syntax`（後述）、および
  `(import (scheme base) (scheme write) (scheme read) (scheme inexact) (scheme cxr) (scheme lazy)
  (scheme process-context) (scheme eval) (scheme repl))`（`only` / `except` /
  `prefix` / `rename` 可）。

9 つのライブラリそれぞれが何をエクスポートするかは[ライブラリ](libraries.md)を、
`import` を一切書かない場合に見える名前は[SICP 互換](sicp.md)を参照してください。

## マクロ

`syntax-rules` 変換子による `define-syntax`、`let-syntax`、`letrec-syntax`:
リテラル、`_`、`...`（入れ子、後続パターン付き、ドット対の末尾、ベクタ内）、
独自の省略記号 `(syntax-rules ::: (literal ...) rule ...)`、`(... ...)` エスケープ、
`syntax-error` に対応します。構文定義はトップレベルか本体の先頭に置けます。
マクロは定義（`begin` を含む）に展開されても構いません。

マクロは健全です。テンプレートが束縛する変数（下の `tmp`）は利用者が書いた名前を
捕捉せず、テンプレートが自由に使う名前（下の `if`）は、利用箇所で何が束縛されていても、
マクロを定義した場所での意味を保ちます。

```scheme
(define-syntax swap!
  (syntax-rules ()
    ((_ a b) (let ((tmp a)) (set! a b) (set! b tmp)))))
(define tmp 1)
(define other 2)
(swap! tmp other)
(display (list tmp other)) (newline)
(define-syntax my-or
  (syntax-rules ()
    ((_) #f)
    ((_ e) e)
    ((_ e r ...) (let ((t e)) (if t t (my-or r ...))))))
(define t 5)
(display (let ((if list)) (my-or #f t))) (newline)
```

```
(2 1)
5
```

制限: 変換子は `syntax-rules` のみです。マクロは定義したファイルの中でだけ見えます
（`(load ...)` したファイルは読み込み元のマクロを見ず、その逆も同じです）。
テンプレートが `define-record-type` に持ち込む名前は改名されません。
`eval` はマクロを知らず、`define-syntax` を拒否します。
