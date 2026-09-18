# REPL

ファイルを指定せずに `--source-language scheme` を付けると Scheme の REPL が起動します。
値は `write` の表記でエコーされます。定義は何もエコーせず、未規定値 -- `display`、`set!`、
`for-each`、どの分岐も選ばれなかった `if` が返すもの。自作の手続きの末尾がそれらでも同じ --
もエコーしません。フォームは複数行にまたがれます。エラーはスタックオーバーフローも含めて
報告され、定義を保ったままセッションが続きます。`(exit)` で終了します。
入力をパイプで与えると、[Common Lisp の REPL](../getting-started/repl.md) と同じく
スクリプト実行器になります: プロンプトを出さず、エラーは標準エラーへ、失敗したフォームが
あれば終了ステータスは 1 です。

```console
$ rontolisp --source-language scheme
scheme> (define (square x) (* x x))
scheme> (map square '(1 2 3))
(1 4 9)
scheme> (set! square -)
scheme> (square 5)
-5
scheme> (list #t #f '() 'Sym)
(#t #f () Sym)
scheme> (define (show x) (display x) (newline))
scheme> (show 'done)
done
scheme> (exit)
```

これら 9 ライブラリがエクスポートする名前 -- それに加えて、
[SICP 互換の名前](sicp.md)（どの `(import ...)` にも属さない）は最初からすべて見えており、
プロンプトで入力した `(import ...)` は名前を追加するだけです。別々のプロンプトで入力した
定義は、1 つのファイルに書いた場合と同じく、順序によらず互いを参照できます。フォームは
入力時点で確定するため、ファイルとの違いが 2 点あります: 組み込み手続き（`square`）を再定義しても、
それ以前に入力したフォームには及びません。また、末尾位置で自分自身を呼ぶ手続きは、後から `set!` で
置き換えても、保持されている古いコピーは自分自身へのループを続けます。
