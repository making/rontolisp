# ライブラリ

ファイルが `import` できる 9 つの R7RS ライブラリと、それぞれが提供するものです。
名前ごとのページは[リファレンス](reference.md)にあります。`(import ...)` で始まるファイル
（[構文](syntax.md)参照）は、名指ししたライブラリだけを見ます。`import` を一切書かない
ファイルは、9 つすべてに加えて
[*Structure and Interpretation of Computer Programs*（SICP）互換の名前](sicp.md)も見えます。

| ライブラリ | 提供するもの |
|---|---|
| [(scheme base)](reference/library-base.md) | 中核部分: 数値、真偽値、ペアとリスト、シンボル、文字、文字列、ベクタ、制御、現在のポートでの入出力。構文は[構文](reference/syntax.md)にあります |
| [(scheme write)](reference/library-write.md) | `display` と `write` |
| [(scheme read)](reference/library-read.md) | `read` |
| [(scheme inexact)](reference/library-inexact.md) | 超越関数と浮動小数点数の述語 |
| [(scheme cxr)](reference/library-cxr.md) | 3 段と 4 段の `car`/`cdr` の合成 |
| [(scheme lazy)](reference/library-lazy.md) | プロミス |
| [(scheme process-context)](reference/library-process-context.md) | `exit` と `emergency-exit` のみ |
| [(scheme eval)](reference/library-eval.md) | `eval` と `environment`。[eval](eval.md) を参照 |
| [(scheme repl)](reference/library-repl.md) | `interaction-environment` |

入出力は現在のポートだけを使います。ポート引数を取る手続きはありません。

```scheme
(define (sum-to n)
  (do ((i 0 (+ i 1)) (sum 0 (+ sum i))) ((> i n) sum)))
(display (sum-to 1000000)) (newline)

(define (first-even items)
  (call/cc (lambda (return)
    (for-each (lambda (x) (if (even? x) (return x))) items)
    #f)))
(display (first-even '(1 3 4 5))) (newline)

(call-with-values (lambda () (values 1 2)) (lambda (a b) (display (+ a b)) (newline)))
```

```
500000500000
4
3
```

## 表示

`write` と `display` は循環するリストやベクタをデータラベル付きで
`#0=(a b c . #0#)` のように書きます。循環のない共有構造は出現のたびに書き出します。

正確な引数に正確な答えがあれば結果も正確なままです。浮動小数点数は `1e21` 未満では
位取り表記で、それ以上（および `1e-6` 未満）では指数付きで表示されます:

```scheme
(write (list (sqrt 16) (sqrt 1/4) (sqrt 2) (exp 0) (atan 0 1))) (newline)
(write (list 123456789.123 1e21 0.000001 1.5e-7 (/ 1.0 0.0))) (newline)
```

```
(4 1/2 1.4142135623730951 1 0)
(123456789.123 1e21 0.000001 1.5e-7 +inf.0)
```
